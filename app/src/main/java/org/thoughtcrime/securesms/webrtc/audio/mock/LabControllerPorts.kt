/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.io.File
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** Test seams are control-plane interfaces; production wraps the real RingRTC
 * endpoint and existing communication router, never a second AudioRecord. */
interface LabNativePort : AutoCloseable {
  fun configure(values:FloatArray):Long
  fun command(op:Int,a:Long=0,b:Long=0,c:Long=0,d:Long=0):Long
  fun load(direction:Int,rate:Int,channels:Int,pcm:ShortArray):Long
  fun status(values:FloatArray):Boolean
  fun drain(tap:Int,metadata:LongArray,pcm:FloatArray):Int
  fun revoke()
}
data class LabRouteChoice(val id:Int,val route:CallRoute,val label:String)
data class LabRouteObservation(val output:LabRouteChoice?,val inputId:Int?,val inputDescription:String,val silenced:Boolean,val mode:Int)
interface LabRoutePort {
  val routeError:Boolean
  fun choices():List<LabRouteChoice>
  fun start(choice:LabRouteChoice)
  fun observe():LabRouteObservation
  fun microphonePermission():Boolean
  fun shutdown():CompletionStage<Void>
}
data class LabHardwareObjects(val native:LabNativePort,val route:LabRoutePort,val backend:String)
data class LabControllerEnvironment(
  val directory:File,
  val acquire:()->LabHardwareObjects,
  val installModels:()->Boolean,
  val isCurrent:()->Boolean,
  val apply:(LabConfiguration,Set<LabSection>)->ApplyResult,
  val onClose:()->Unit,
  val retirement:Executor,
  val disposal:Executor,
  val nowMs:()->Long={System.nanoTime()/1_000_000}
)
