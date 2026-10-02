/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import org.thoughtcrime.securesms.webrtc.audio.DenoiseSettings
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioSettings

enum class LabMode { RECEIVED, SENT, BOTH }
enum class Monitor { RECEIVED, SENT }
enum class Tap { BACKEND_INPUT, BEFORE_SENT, AFTER_SENT, PLAYBACK_REFERENCE }
enum class LabSection { RECEIVED_DENOISE, SENT_DENOISE, RECEIVED_EFFECTS }
data class LabToken(val id: Long, val generation: Long) {
  init { require(id > 0 && generation > 0) }
}
/** Immutable, versioned configuration; no transport state or recording identity. */
data class LabConfiguration(
  val received: DenoiseSettings = DenoiseSettings(),
  val sent: DenoiseSettings = DenoiseSettings(),
  val effects: IncomingAudioSettings = IncomingAudioSettings()
) {
  fun valid(): Boolean = received == received.sanitized() && sent == sent.sanitized() && effects == effects.sanitized()
  fun selectedFrom(other: LabConfiguration, sections: Set<LabSection>) = copy(
    received = if (LabSection.RECEIVED_DENOISE in sections) other.received else received,
    sent = if (LabSection.SENT_DENOISE in sections) other.sent else sent,
    effects = if (LabSection.RECEIVED_EFFECTS in sections) other.effects.copy(eq = other.effects.eq.toList()) else effects
  )
  fun toStored(): Map<String, String> = buildMap {
    put("schema", "1")
    received.toStored().forEach { (k, v) -> put("received.$k", v) }
    sent.toStored().forEach { (k, v) -> put("sent.$k", v) }
    effects.wire().forEachIndexed { i, v -> put("effect.$i", v.toString()) }
  }
  companion object {
    fun fromStored(values: Map<String, String>): LabConfiguration? {
      if (values["schema"] != "1") return null
      fun read(prefix: String) = values.filterKeys { it.startsWith("$prefix.") }.mapKeys { it.key.removePrefix("$prefix.") }
      val defaults = DenoiseSettings().toStored()
      for (prefix in listOf("received", "sent")) {
        val part = read(prefix)
        if (part.keys != defaults.keys) return null
        // Existing schema parser is reused; reject, rather than conceal, invalid stored values.
        val parsed = DenoiseSettings.fromStored(part)
        if (parsed.toStored() != part) return null
      }
      val wire = (0 until 24).map { values["effect.$it"]?.toFloatOrNull() ?: return null }
      if (wire.any { !it.isFinite() } || listOf(0,1,2,3,23).any { wire[it] != 0f && wire[it] != 1f }) return null
      val effect = IncomingAudioSettings(
        enabled = wire[0] == 1f, eqEnabled = wire[1] == 1f, compressorEnabled = wire[2] == 1f,
        limiterEnabled = wire[3] == 1f, gainEnabled = wire[23] == 1f, gain = wire[4], threshold = wire[5],
        ratio = wire[6], attack = wire[7], release = wire[8], knee = wire[9], makeup = wire[10], ceiling = wire[11],
        limiterRelease = wire[12], eq = wire.subList(13, 23).toList()
      )
      return LabConfiguration(DenoiseSettings.fromStored(read("received")), DenoiseSettings.fromStored(read("sent")), effect).takeIf { it.valid() }
    }
  }
}
