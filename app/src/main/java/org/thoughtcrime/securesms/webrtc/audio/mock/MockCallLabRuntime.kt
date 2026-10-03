/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import android.content.Context
import org.thoughtcrime.securesms.webrtc.audio.CallDenoiseController
import org.thoughtcrime.securesms.webrtc.audio.Direction
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The only bridge from real call startup into the local lab. No signal messages,
 * contacts, account credentials or call history are handled here. */
object MockCallLabRuntime {
  internal val retirement:ExecutorService=Executors.newFixedThreadPool(2) { task -> Thread(task,"mock-hardware-retirement").apply{isDaemon=true} }
  internal val disposal:ExecutorService=Executors.newFixedThreadPool(2) { task -> Thread(task,"mock-model-disposal").apply{isDaemon=true} }
  @Volatile private var active:MockCallLabController?=null
  private var pendingRealActions=0
  @Volatile private var retiring:CompletionStage<Void> = CompletableFuture.completedFuture(null)
  internal val coordinator=LabCoordinator(
    readProduction={
      val d=CallDenoiseController.state.value.settings
      LabConfiguration(d.received,d.sent,IncomingAudioController.settings.value)
    },
    publishProduction={v->
      // Coordinator excludes call startup until the complete selected snapshot
      // is published. The original preference formats remain unchanged.
      CallDenoiseController.update(Direction.RECEIVED,v.received,false)
      CallDenoiseController.update(Direction.SENT,v.sent,false)
      IncomingAudioController.update(v.effects,false)
      CallDenoiseController.save();IncomingAudioController.save()
    },
    revokeNative={token->active?.takeIf{it.token==token}?.preempt()?:CompletableFuture.completedFuture(null)}
  )

  @Synchronized fun open(context:Context):MockCallLabController? {
    IncomingAudioController.initialize(context)
    val token=coordinator.open()?:return null
    val configuration=coordinator.initialConfiguration(token)?:return null
    return MockCallLabController(token,configuration,LabAndroidHardware.environment(context.applicationContext,token)).also{active=it}
  }
  internal fun valid(token:LabToken):Boolean=coordinator.check(token)
  @Synchronized internal fun close(token:LabToken){coordinator.close(token);if(active?.token==token)active=null}

  @JvmStatic @Synchronized fun reserveRealCall() {
    pendingRealActions++
    retiring=coordinator.preemptForRealCall()
  }
  /** Only the existing call service executor waits here; never the UI or audio. */
  @JvmStatic fun awaitHardwareStop():Boolean = try {
    retiring.toCompletableFuture().get(2,TimeUnit.SECONDS);true
  } catch(_:Exception){false}
  @JvmStatic @Synchronized fun realStateChanged(ownsAudio:Boolean,finishedStartAction:Boolean) {
    if(finishedStartAction&&pendingRealActions>0)pendingRealActions--
    if(ownsAudio)retiring=coordinator.preemptForRealCall()
    else if(pendingRealActions==0)coordinator.realCallFinished()
  }
}
