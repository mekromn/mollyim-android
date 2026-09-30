import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioSettings
fun main() {
  val defaults = IncomingAudioSettings()
  check(!defaults.enabled)
  check(defaults.wire().size == 24)
  check(defaults.wire()[0] == 0f && defaults.wire()[23] == 1f)
  val changed = defaults.copy(enabled = true, gain = 6f, eq = List(10) { it.toFloat() })
  check(changed.wire()[4] == 6f)
  for (i in 0..9) check(changed.wire()[13+i] == i.toFloat())
  val invalid = defaults.copy(gain = Float.NaN, ratio = 0f, ceiling = 100f, eq = listOf(Float.POSITIVE_INFINITY, -100f)).sanitized()
  check(invalid.gain == 0f && invalid.ratio == 1f && invalid.ceiling == -0.1f)
  check(invalid.eq == listOf(0f, -12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
  check(invalid.wire().all { it.isFinite() })
  println("PASS Kotlin defaults, native wire layout, finite values and bounds")
}
