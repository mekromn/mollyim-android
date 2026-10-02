/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only settings are persisted. No call samples or identifiers are stored. */
@Keep
object IncomingAudioController {
  private val mutableSettings = MutableStateFlow(IncomingAudioSettings())
  val settings = mutableSettings.asStateFlow()
  private var preferences: SharedPreferences? = null
  val available: Boolean get() = IncomingAudioBridge.isAvailable()

  // Called by the patched RingRTC initializer, including background incoming calls.
  @JvmStatic
  @Synchronized
  fun initialize(context: Context) {
    CallDenoiseController.initialize(context)
    if (preferences != null) return
    val prefs = context.applicationContext.getSharedPreferences("incoming_audio_v1", Context.MODE_PRIVATE)
    preferences = prefs
    val defaults = IncomingAudioSettings()
    val saved = try {
      defaults.copy(
        enabled = prefs.getBoolean("enabled", false), eqEnabled = prefs.getBoolean("eqEnabled", true),
        compressorEnabled = prefs.getBoolean("compressorEnabled", true), limiterEnabled = prefs.getBoolean("limiterEnabled", true),
        gainEnabled = prefs.getBoolean("gainEnabled", true), gain = prefs.getFloat("gain", defaults.gain),
        threshold = prefs.getFloat("threshold", defaults.threshold), ratio = prefs.getFloat("ratio", defaults.ratio),
        attack = prefs.getFloat("attack", defaults.attack), release = prefs.getFloat("release", defaults.release),
        knee = prefs.getFloat("knee", defaults.knee), makeup = prefs.getFloat("makeup", defaults.makeup),
        ceiling = prefs.getFloat("ceiling", defaults.ceiling), limiterRelease = prefs.getFloat("limiterRelease", defaults.limiterRelease),
        eq = List(10) { prefs.getFloat("eq$it", 0f) }
      ).sanitized()
    } catch (_: ClassCastException) {
      defaults
    }
    mutableSettings.value = saved
    IncomingAudioBridge.reset()
    IncomingAudioBridge.apply(saved.wire())
  }

  @Synchronized
  fun update(value: IncomingAudioSettings, persist: Boolean = true) {
    val safe = value.sanitized()
    mutableSettings.value = safe
    IncomingAudioBridge.apply(safe.wire())
    if (persist) save()
  }

  @Synchronized
  fun save() {
    val s = mutableSettings.value
    val editor = preferences?.edit() ?: return
    editor.putBoolean("enabled", s.enabled).putBoolean("eqEnabled", s.eqEnabled)
      .putBoolean("compressorEnabled", s.compressorEnabled).putBoolean("limiterEnabled", s.limiterEnabled)
      .putBoolean("gainEnabled", s.gainEnabled).putFloat("gain", s.gain).putFloat("threshold", s.threshold)
      .putFloat("ratio", s.ratio).putFloat("attack", s.attack).putFloat("release", s.release)
      .putFloat("knee", s.knee).putFloat("makeup", s.makeup).putFloat("ceiling", s.ceiling)
      .putFloat("limiterRelease", s.limiterRelease)
    s.eq.forEachIndexed { i, value -> editor.putFloat("eq$i", value) }
    editor.apply()
  }
}
