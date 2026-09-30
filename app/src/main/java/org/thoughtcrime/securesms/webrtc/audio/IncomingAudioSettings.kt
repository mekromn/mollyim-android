/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

/** Units: dB/dBFS and milliseconds. Wire layout is versioned with native ABI 1. */
data class IncomingAudioSettings(
  val enabled: Boolean = false,
  val eqEnabled: Boolean = true,
  val compressorEnabled: Boolean = true,
  val limiterEnabled: Boolean = true,
  val gainEnabled: Boolean = true,
  val gain: Float = 0f,
  val threshold: Float = -24f,
  val ratio: Float = 3f,
  val attack: Float = 10f,
  val release: Float = 120f,
  val knee: Float = 6f,
  val makeup: Float = 0f,
  val ceiling: Float = -1f,
  val limiterRelease: Float = 80f,
  val eq: List<Float> = List(10) { 0f }
) {
  fun sanitized() = copy(
    gain = gain.bounded(-24f, 18f, 0f), threshold = threshold.bounded(-60f, 0f, -24f),
    ratio = ratio.bounded(1f, 20f, 3f), attack = attack.bounded(0.1f, 100f, 10f),
    release = release.bounded(10f, 1000f, 120f), knee = knee.bounded(0f, 24f, 6f),
    makeup = makeup.bounded(0f, 18f, 0f), ceiling = ceiling.bounded(-24f, -0.1f, -1f),
    limiterRelease = limiterRelease.bounded(10f, 1000f, 80f),
    eq = List(10) { eq.getOrElse(it) { 0f }.bounded(-12f, 12f, 0f) }
  )

  fun wire(): FloatArray {
    val s = sanitized()
    return floatArrayOf(
      s.enabled.number(), s.eqEnabled.number(), s.compressorEnabled.number(), s.limiterEnabled.number(),
      s.gain, s.threshold, s.ratio, s.attack, s.release, s.knee, s.makeup, s.ceiling, s.limiterRelease,
      *s.eq.toFloatArray(), s.gainEnabled.number()
    )
  }

  private fun Boolean.number() = if (this) 1f else 0f
  private fun Float.bounded(low: Float, high: Float, fallback: Float) = if (isFinite()) coerceIn(low, high) else fallback
}
