package org.thoughtcrime.securesms.webrtc.audio

import org.junit.Assert.*
import org.junit.Test

class CallDenoiseSettingsTest {
  @Test fun defaults_off_and_isolated() {
    val pair = DenoisePair()
    assertFalse(pair.received.enabled)
    assertFalse(pair.sent.enabled)
    assertEquals(Model.STANDARD, pair.sent.model)
    assertEquals(30f, pair.sent.attenuationDb, 0f)
    assertEquals(0.02f, pair.sent.beta, 0f)
    assertFalse(pair.sent.postFilter)
    assertEquals(-10f, pair.sent.minSnr, 0f)
    assertEquals(30f, pair.sent.erbSnr, 0f)
    assertEquals(20f, pair.sent.dfSnr, 0f)
    assertEquals(pair.received, pair.updated(Direction.SENT, DenoiseSettings(enabled = true)).received)
  }
  @Test fun presets_preserve_enable_model_thresholds() {
    val original = DenoiseSettings(enabled = false, model = Model.LOW_LATENCY, minSnr = -20f, dfSnr = 22f, erbSnr = 40f)
    for (preset in listOf(Preset.GENTLE, Preset.BALANCED, Preset.STRONG)) {
      val value = original.preset(preset)
      assertFalse(value.enabled); assertEquals(original.model, value.model)
      assertEquals(-20f, value.minSnr, 0f); assertEquals(22f, value.dfSnr, 0f); assertEquals(40f, value.erbSnr, 0f)
    }
    assertEquals(12f, original.preset(Preset.GENTLE).attenuationDb, 0f)
    assertEquals(30f, original.preset(Preset.BALANCED).attenuationDb, 0f)
    assertEquals(100f, original.preset(Preset.STRONG).attenuationDb, 0f)
    assertTrue(original.preset(Preset.STRONG).postFilter)
    assertEquals(0.02f, original.preset(Preset.STRONG).beta, 0f)
    assertEquals(original, original.preset(Preset.CUSTOM))
  }
  @Test fun reset_is_direction_local() {
    val pair = DenoisePair(DenoiseSettings(enabled=true, attenuationDb=80f), DenoiseSettings(enabled=true))
    val reset = pair.updated(Direction.SENT, DenoiseSettings())
    assertEquals(pair.received, reset.received); assertFalse(reset.sent.enabled)
  }
  @Test fun reject_nonfinite_and_order_thresholds() {
    val safe = DenoiseSettings(attenuationDb=Float.NaN,beta=Float.POSITIVE_INFINITY,minSnr=80f,dfSnr=-50f,erbSnr=-20f).sanitized()
    assertEquals(30f,safe.attenuationDb,0f); assertEquals(0.02f,safe.beta,0f)
    assertEquals(60f,safe.minSnr,0f); assertEquals(60f,safe.dfSnr,0f); assertEquals(60f,safe.erbSnr,0f)
    val rounded = DenoiseSettings(attenuationDb=30.8f,beta=0.0208f,minSnr=-10.2f).sanitized()
    assertEquals(31f,rounded.attenuationDb,0f); assertEquals(0.021f,rounded.beta,0f)
    assertEquals(-10f,rounded.minSnr,0f)
  }
  @Test fun serialization_round_trip() {
    val original = DenoiseSettings(enabled=true,model=Model.LOW_LATENCY,attenuationDb=45f,postFilter=true,beta=0.032f)
    assertEquals(original,DenoiseSettings.fromStored(original.toStored()))
    assertEquals(DenoiseSettings(), DenoiseSettings.fromStored(original.toStored()+mapOf("model" to "88")))
    assertEquals(DenoiseSettings(), DenoiseSettings.fromStored(original.toStored()+mapOf("version" to "99")))
    assertEquals(DenoiseSettings(), DenoiseSettings.fromStored(original.toStored()+mapOf("attenuationDb" to "NaN")))
    assertEquals(0,Direction.RECEIVED.wireId); assertEquals(1,Direction.SENT.wireId)
    assertEquals(0,Model.STANDARD.wireId); assertEquals(1,Model.LOW_LATENCY.wireId)
  }
}
