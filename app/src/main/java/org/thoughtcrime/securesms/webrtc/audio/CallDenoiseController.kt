/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared by foreground panels and background calls; no audio or account state. */
@Keep
object CallDenoiseController {
  private val mutableState = MutableStateFlow(DenoiseUiSnapshot())
  val state = mutableState.asStateFlow()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var coordinator: DenoiseCoordinator? = null

  @JvmStatic
  @Synchronized
  fun initialize(context: Context) {
    if (coordinator == null) {
      val app = context.applicationContext
      val store = object : DenoiseStore {
        override fun load(): DenoisePair {
          val all = app.getSharedPreferences("call_denoise_v1", Context.MODE_PRIVATE).all
          fun read(prefix: String): DenoiseSettings = DenoiseSettings.fromStored(
            all.filterKeys { it.startsWith("$prefix.") }.mapNotNull { (key, value) ->
              (value as? String)?.let { key.removePrefix("$prefix.") to it }
            }.toMap()
          )
          return DenoisePair(read("received"), read("sent"))
        }
        override fun save(value: DenoisePair) {
          val editor = app.getSharedPreferences("call_denoise_v1", Context.MODE_PRIVATE).edit()
          value.received.toStored().forEach { (key, v) -> editor.putString("received.$key", v) }
          value.sent.toStored().forEach { (key, v) -> editor.putString("sent.$key", v) }
          editor.apply()
        }
      }
      val native = object : DenoiseNative {
        override fun available() = CallDenoiseBridge.available()
        override fun apply(direction: Direction, settings: DenoiseSettings) = CallDenoiseBridge.apply(
          direction.wireId, settings.enabled, settings.model.wireId, settings.attenuationDb,
          settings.postFilter, settings.beta, settings.minSnr, settings.erbSnr, settings.dfSnr
        )
        override fun bypass(direction: Direction, value: Boolean) = CallDenoiseBridge.bypass(direction.wireId, value)
        override fun retry(direction: Direction) = CallDenoiseBridge.retry(direction.wireId)
      }
      lateinit var created: DenoiseCoordinator
      created = DenoiseCoordinator(native, store, { generation ->
        scope.launch {
          val success = try { DeepFilterAssets.install(app) } catch (_: Exception) { false }
          created.assetsCompleted(generation, success)
        }
      }, { mutableState.value = it })
      coordinator = created
    }
    // Rechecks native availability after RingRTC initialization without resetting
    // an already active worker or re-reading/replacing the user's current edits.
    coordinator?.initialize()
  }

  fun update(direction: Direction, settings: DenoiseSettings, persist: Boolean = true) = coordinator?.update(direction, settings, persist)
  fun save() = coordinator?.save()
  fun setBypassed(direction: Direction, bypassed: Boolean) = coordinator?.bypass(direction, bypassed)
  fun retry(direction: Direction) = coordinator?.retry(direction)
  fun reset(direction: Direction) = coordinator?.reset(direction)

  fun readStatus(direction: Direction): DenoiseStatus {
    val values = FloatArray(16)
    return if (CallDenoiseBridge.status(direction.wireId, values)) DenoiseStatus.fromNative(values) else DenoiseStatus()
  }
}

/** Small numeric snapshot only. Filter delay is not whole-call network latency. */
data class DenoiseStatus(
  val effective: Int = 4,
  val rate: Int = 0,
  val channels: Int = 0,
  val delayMs: Float = 0f,
  val misses: Int = 0,
  val inputPeak: Float = 0f,
  val outputPeak: Float = 0f,
  val snr: Float = 0f,
  val meanUs: Float = 0f,
  val p95Us: Float = 0f,
  val levelsValid: Boolean = false,
  val inferenceValid: Boolean = false,
  val bypassed: Boolean = false
) {
  companion object {
    fun fromNative(v: FloatArray): DenoiseStatus {
      if (v.size != 16 || v.any { !it.isFinite() } || v[0].toInt() !in 0..8) return DenoiseStatus()
      return DenoiseStatus(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3], v[4].toInt(),
        v[5], v[6], v[7], v[8], v[9], v[10] != 0f, v[11] != 0f, v[14] != 0f)
    }
  }
}
