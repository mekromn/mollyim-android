package org.thoughtcrime.securesms.webrtc.audio.mock
import org.thoughtcrime.securesms.webrtc.audio.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
fun main() {
  var production=LabConfiguration()
  var writes=0
  val retired=mutableListOf<LabToken>()
  val c=LabCoordinator({production},{production=it;writes++},{t->retired+=t;CompletableFuture.completedFuture(null)})
  val token=c.open()!!
  var settings=c.initialConfiguration(token)!!
  settings=settings.copy(received=settings.received.copy(enabled=true),sent=settings.sent.copy(attenuationDb=77f),effects=settings.effects.copy(enabled=true,gain=9f))
  check(!production.received.enabled&&production.effects.gain==0f&&writes==0)
  val result=c.applyToCalls(token,settings,setOf(LabSection.SENT_DENOISE))
  check(result.applied&&production.sent.attenuationDb==77f&&!production.received.enabled&&!production.effects.enabled&&writes==1)
  check(!c.applyToCalls(token,settings.copy(effects=settings.effects.copy(gain=Float.NaN)),setOf(LabSection.RECEIVED_EFFECTS)).applied)
  check(c.check(token));c.close(token);check(!c.check(token));check(writes==1)
  val t2=c.open()!!;check(t2.id>token.id)
  c.preemptForRealCall().toCompletableFuture().join()
  check(!c.check(t2)&&!c.applyToCalls(t2,settings,LabSection.entries.toSet()).applied&&writes==1)
  check(c.open()==null)
  c.realCallFinished();val t3=c.open()!!;c.close(token);check(c.check(t3))
  check(!LabConfiguration.fromStored(mapOf("schema" to "999")).let { it!=null })
  check(LabConfiguration.fromStored(settings.toStored())==settings)
  val r=LabCoordinator({LabConfiguration()},{},{CompletableFuture.completedFuture(null)})
  val start=CountDownLatch(1);val done=CountDownLatch(1)
  thread {start.await();r.preemptForRealCall();done.countDown()}
  val pending=r.open()!!;start.countDown();done.await()
  check(!r.check(pending)&&r.open()==null)
  c.close(t3)
  val failedStop = LabCoordinator({LabConfiguration()},{},{CompletableFuture<Void>().also { it.completeExceptionally(IllegalStateException("device stop failed")) }})
  val failedToken = failedStop.open()!!;failedStop.close(failedToken)
  check(failedStop.open() == null)
  println("PASS Kotlin lab isolation, explicit selected Apply, rejected invalid input, schema roundtrip, preemption/open race and stale close")
}
