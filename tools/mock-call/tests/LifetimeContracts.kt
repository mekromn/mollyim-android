/* SPDX-License-Identifier: AGPL-3.0-only */
import org.thoughtcrime.securesms.webrtc.audio.mock.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private class Hardware: LabHardwareLifetime {
 var revoked=0;var released=0
 val stopped=CompletableFuture<Void>()
 override fun revoke(){revoked++}
 override fun stopHardware():CompletionStage<Void> = stopped
 override fun releaseAfterStop(){released++}
}
fun main(){
 val jobs=ArrayList<Runnable>()
 val lifetime=LabLifecycle { jobs.add(it) }
 val acquisition=lifetime.beginAcquisition()!!
 val hardware=Hardware()
 check(lifetime.attach(acquisition,hardware))
 val end=lifetime.revoke().toCompletableFuture()
 check(hardware.revoked==1 && !end.isDone && lifetime.beginAcquisition()==null)
 hardware.stopped.complete(null)
 check(end.isDone && hardware.released==0)
 check(!lifetime.disposed().toCompletableFuture().isDone)
 jobs.removeAt(0).run();check(hardware.released==1)
 check(lifetime.disposed().toCompletableFuture().isDone)
 val pending=LabLifecycle {jobs.add(it)}
 val request=pending.beginAcquisition()!!
 val cancelled=pending.revoke().toCompletableFuture()
 check(!cancelled.isDone)
 val late=Hardware();check(!pending.attach(request,late));check(late.revoked==1 && !cancelled.isDone)
 late.stopped.complete(null);check(cancelled.isDone);jobs.removeAt(0).run();check(late.released==1)
 val failed=LabLifecycle {jobs.add(it)}
 val first=failed.beginAcquisition()!!;val fault=Hardware();check(failed.attach(first,fault))
 val blocked=failed.revoke().toCompletableFuture()
 fault.stopped.completeExceptionally(IllegalStateException("Driver failed"))
 check(blocked.isCompletedExceptionally && fault.released==0)
 val creation=LabLifecycle {jobs.add(it)}
 val token=creation.beginAcquisition()!!;val retired=creation.revoke().toCompletableFuture()
 creation.failedAcquisition(token);check(retired.isDone)
 println("PASS: immediate revocation, late acquisition, hardware-only handoff and separate disposal")
}
