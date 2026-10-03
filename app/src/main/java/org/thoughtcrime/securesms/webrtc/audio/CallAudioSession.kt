/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CallAudioUiState(
  val denoise: DenoiseUiSnapshot = DenoiseUiSnapshot(),
  val effects: IncomingAudioSettings = IncomingAudioSettings()
)
data class CallAudioStatus(val received: DenoiseStatus, val sent: DenoiseStatus)

/** UI uses this scope, never decides whether an edit belongs to a real call. */
interface CallAudioSession {
  val state: StateFlow<CallAudioUiState>
  val effectsAvailable: Boolean
  fun initialize(context: Context) {}
  fun updateDenoise(direction: Direction, settings: DenoiseSettings)
  fun updateEffects(settings: IncomingAudioSettings)
  fun setBypassed(direction: Direction, bypassed: Boolean)
  fun retry(direction: Direction)
  fun reset(direction: Direction)
  fun resetEffects()
  fun save()
  fun readStatus(): CallAudioStatus
  /** Returns monotonically changing callback count, or -1 when unavailable. */
  fun readEffectsMeters(values: FloatArray): Long
}

/** Ordinary calls retain the existing preference keys and native controllers. */
object ProductionCallAudioSession : CallAudioSession {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  override val state: StateFlow<CallAudioUiState> = combine(CallDenoiseController.state, IncomingAudioController.settings) { d, e ->
    CallAudioUiState(d, e)
  }.stateIn(scope, SharingStarted.Eagerly, CallAudioUiState(CallDenoiseController.state.value, IncomingAudioController.settings.value))
  override val effectsAvailable: Boolean get() = IncomingAudioController.available
  override fun initialize(context: Context) = IncomingAudioController.initialize(context)
  override fun updateDenoise(direction: Direction, settings: DenoiseSettings) { CallDenoiseController.update(direction, settings, false) }
  override fun updateEffects(settings: IncomingAudioSettings) = IncomingAudioController.update(settings, false)
  override fun setBypassed(direction: Direction, bypassed: Boolean) { CallDenoiseController.setBypassed(direction, bypassed) }
  override fun retry(direction: Direction) { CallDenoiseController.retry(direction) }
  override fun reset(direction: Direction) { CallDenoiseController.reset(direction) }
  override fun resetEffects() { IncomingAudioBridge.reset(); IncomingAudioController.update(IncomingAudioSettings()) }
  override fun save() { IncomingAudioController.save(); CallDenoiseController.save() }
  override fun readStatus() = CallAudioStatus(CallDenoiseController.readStatus(Direction.RECEIVED), CallDenoiseController.readStatus(Direction.SENT))
  override fun readEffectsMeters(values: FloatArray): Long = IncomingAudioBridge.readMeters(values)
}
