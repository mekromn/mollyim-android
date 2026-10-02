package org.thoughtcrime.securesms.webrtc.audio.mock
import org.junit.Assert.*
import org.junit.Test
class LabConfigurationTest {
  @Test fun selectedSectionsAndRoundTrip() {
    val baseline=LabConfiguration()
    val lab=baseline.copy(sent=baseline.sent.copy(enabled=true,attenuationDb=42f),effects=baseline.effects.copy(gain=8f))
    assertEquals(lab,LabConfiguration.fromStored(lab.toStored()))
    assertEquals(baseline.effects,baseline.selectedFrom(lab,setOf(LabSection.SENT_DENOISE)).effects)
    assertTrue(baseline.selectedFrom(lab,setOf(LabSection.SENT_DENOISE)).sent.enabled)
    assertFalse(baseline.sent.enabled)
  }
  @Test fun corruptSchemaAndNonfiniteRejected(){
    assertNull(LabConfiguration.fromStored(mapOf("schema" to "2")))
    assertFalse(LabConfiguration(effects=org.thoughtcrime.securesms.webrtc.audio.IncomingAudioSettings(gain=Float.NaN)).valid())
  }
}
