package org.thoughtcrime.securesms.webrtc.audio.mock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
class LabCoordinatorTest {
  @Test fun preemptionAndStaleApply(){
    var settings=LabConfiguration();var writes=0
    val c=LabCoordinator({settings},{settings=it;writes++},{CompletableFuture.completedFuture(null)})
    val token=c.open()!!
    assertEquals(0,writes)
    assertTrue(c.applyToCalls(token,settings.copy(sent=settings.sent.copy(enabled=true)),setOf(LabSection.SENT_DENOISE)).applied)
    assertTrue(settings.sent.enabled);assertFalse(settings.received.enabled)
    c.preemptForRealCall()
    assertFalse(c.applyToCalls(token,LabConfiguration(),LabSection.entries.toSet()).applied)
    assertNull(c.open());assertEquals(1,writes)
    c.realCallFinished();val newer=c.open()!!;c.close(token);assertTrue(c.check(newer))
  }
  @Test fun pendingOrFailedDeviceStopBlocksReentry(){
    val stop=CompletableFuture<Void>();val c=LabCoordinator({LabConfiguration()},{},{stop})
    val t=c.open()!!;c.close(t);assertNull(c.open())
    stop.completeExceptionally(IllegalStateException());assertNull(c.open())
  }
}
