/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

/** Control plane only. Implementations never read microphone samples. */
internal interface DenoiseNative {
  fun available(): Boolean
  fun apply(direction: Direction, settings: DenoiseSettings): Boolean
  fun bypass(direction: Direction, value: Boolean)
  fun retry(direction: Direction)
}
internal interface DenoiseStore {
  fun load(): DenoisePair
  fun save(value: DenoisePair)
}

data class DenoiseUiSnapshot(
  val settings: DenoisePair = DenoisePair(),
  val nativeAvailable: Boolean = false,
  val assetsReady: Boolean = false,
  val assetsLoading: Boolean = false,
  val assetsFailed: Boolean = false,
  val storageAvailable: Boolean = true
)

/** All mutations are serialized, but asset installation runs on a separate IO worker.
 * A late installer completion always applies the latest settings, not its old request.
 * Neither constructing this class nor editing disabled settings loads a model.
 */
internal class DenoiseCoordinator(
  private val native: DenoiseNative,
  private val store: DenoiseStore,
  private val requestAssets: (Long) -> Unit,
  private val changed: (DenoiseUiSnapshot) -> Unit
) {
  private var initialized = false
  private var state = DenoiseUiSnapshot()
  private var assetGeneration = 0L
  private val applied = mutableMapOf<Direction, DenoiseSettings>()

  @Synchronized fun snapshot(): DenoiseUiSnapshot = state

  @Synchronized fun initialize() {
    if (!initialized) {
      initialized = true
      state = try {
        state.copy(settings = store.load())
      } catch (_: Exception) {
        state.copy(storageAvailable = false)
      }
    }
    reconcile()
  }

  @Synchronized fun update(direction: Direction, settings: DenoiseSettings, persist: Boolean = true) {
    state = state.copy(settings = state.settings.updated(direction, settings))
    reconcile()
    if (persist) save()
  }

  @Synchronized fun save() {
    if (!initialized || !state.storageAvailable) return
    try {
      store.save(state.settings)
    } catch (_: Exception) {
      state = state.copy(storageAvailable = false)
      changed(state)
    }
  }

  @Synchronized fun assetsCompleted(generation: Long, success: Boolean) {
    if (generation != assetGeneration || !state.assetsLoading) return
    state = state.copy(assetsReady = success, assetsLoading = false, assetsFailed = !success)
    reconcile()
  }

  @Synchronized fun bypass(direction: Direction, value: Boolean) {
    if (state.nativeAvailable) native.bypass(direction, value)
  }

  @Synchronized fun retry(direction: Direction) {
    if (state.assetsFailed) state = state.copy(assetsFailed = false)
    if (state.nativeAvailable) native.retry(direction)
    reconcile()
  }

  @Synchronized fun reset(direction: Direction) {
    bypass(direction, false)
    update(direction, DenoiseSettings())
  }

  private fun reconcile() {
    val available = native.available()
    if (!available) applied.clear()
    state = state.copy(nativeAvailable = available)
    if (available) {
      Direction.entries.forEach { direction ->
        val requested = state.settings[direction]
        val effective = if (requested.enabled && !state.assetsReady) requested.copy(enabled = false) else requested
        if (applied[direction] != effective) {
          if (native.apply(direction, effective)) applied[direction] = effective
          else state = state.copy(nativeAvailable = false)
        }
      }
    }
    val needsAssets = state.settings.received.enabled || state.settings.sent.enabled
    if (state.nativeAvailable && needsAssets && !state.assetsReady && !state.assetsLoading && !state.assetsFailed) {
      assetGeneration++
      state = state.copy(assetsLoading = true)
      changed(state)
      requestAssets(assetGeneration)
    } else {
      changed(state)
    }
  }
}
