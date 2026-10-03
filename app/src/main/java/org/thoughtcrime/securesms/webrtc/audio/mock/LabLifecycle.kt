/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

interface LabHardwareLifetime {
  /** Atomically closes injection, monitor and recording gates; never waits for IO. */
  fun revoke()
  /** Completes only after physical streams and the old route/focus owner stop. */
  fun stopHardware(): CompletionStage<Void>
  /** Model/factory retirement can take longer and must not delay real-call setup. */
  fun releaseAfterStop()
}

/** One acquisition per local native lifetime. A closed lifetime is never reused.
 * Revocation racing factory creation retains a future for that pending factory;
 * it cannot falsely signal that all hardware is free before the factory returns.
 */
class LabLifecycle(private val disposer: Executor) {
  private var generation=0L
  private var acquiring=false
  private var revoked=false
  private var hardware:LabHardwareLifetime?=null
  private val stopped=CompletableFuture<Void>()
  private val released=CompletableFuture<Void>()
  fun disposed():CompletionStage<Void> = released

  @Synchronized fun beginAcquisition():Long? {
    if(revoked||acquiring||hardware!=null||generation!=0L)return null
    acquiring=true;generation=1;return generation
  }
  @Synchronized fun attach(ticket:Long,value:LabHardwareLifetime):Boolean {
    require(ticket==generation&&acquiring) { "Acquisition ticket is not current" }
    acquiring=false;hardware=value
    if(revoked){retire(value);return false}
    return true
  }
  @Synchronized fun failedAcquisition(ticket:Long) {
    require(ticket==generation&&acquiring)
    acquiring=false
    if(revoked){stopped.complete(null);released.complete(null)}
  }
  @Synchronized fun revoke():CompletionStage<Void> {
    if(revoked)return stopped
    revoked=true
    val current=hardware
    if(current!=null)retire(current)
    else if(!acquiring){stopped.complete(null);released.complete(null)}
    return stopped
  }
  private fun retire(value:LabHardwareLifetime) {
    try {
      value.revoke()
      value.stopHardware().whenComplete { _,error ->
        if(error!=null){stopped.completeExceptionally(error);released.completeExceptionally(error)}
        else {
          stopped.complete(null)
          // No model destructor, filesystem operation or thread join occurs in
          // the completion that permits the next real call to acquire audio.
          disposer.execute {
            try{value.releaseAfterStop();released.complete(null)}catch(e:Exception){released.completeExceptionally(e)}
          }
        }
      }
    } catch(e:Exception){stopped.completeExceptionally(e);released.completeExceptionally(e)}
  }
}
