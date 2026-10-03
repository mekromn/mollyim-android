/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

fun main() {
  val token=LabToken(1,1)
  var invalidations=0
  val routes=LabRouteController(token) { invalidations++ }
  routes.environment(visible=true,microphonePermission=true)
  val handset=requireNotNull(routes.request(CallRoute.HANDSET,11))
  check(!routes.snapshot().outputConfirmed && !routes.mayOpenMicrophone(handset))
  check(routes.confirm(handset,RouteObservation(CallRoute.HANDSET,11,21,1,true)))
  check(routes.snapshot().outputConfirmed)
  check(!routes.mayOpenMicrophone(handset)) // Confirmation never creates consent.
  check(routes.recordPressed(handset))
  check(routes.mayOpenMicrophone(handset) && routes.acceptCapturedBlock(handset,1))
  val speaker=requireNotNull(routes.request(CallRoute.SPEAKERPHONE,12))
  check(!routes.acceptCapturedBlock(handset,1) && !routes.confirm(handset,RouteObservation(CallRoute.HANDSET,11,21,1,true)))
  check(!routes.confirm(speaker,RouteObservation(CallRoute.HANDSET,11,21,2,true)))
  check(routes.confirm(speaker,RouteObservation(CallRoute.SPEAKERPHONE,12,21,2,true)))
  check(routes.snapshot().actual?.inputDeviceId==21) // Same input ID does not erase the real output-route change.
  check(routes.snapshot().requested==CallRoute.SPEAKERPHONE)
  check(routes.snapshot().segment>handset.segment)
  check(routes.mayOpenMicrophone(speaker) && routes.acceptCapturedBlock(speaker,2))
  check(!routes.acceptCapturedBlock(speaker,1))
  routes.environment(visible=true,microphonePermission=false)
  check(!routes.mayOpenMicrophone(speaker) && !routes.acceptCapturedBlock(speaker,2))
  routes.environment(visible=true,microphonePermission=true)
  check(!routes.mayOpenMicrophone(speaker)) // Grant alone cannot restart recording.
  check(!routes.recordPressed(speaker)) // Old permission epoch cannot be reused.
  val restored=requireNotNull(routes.request(CallRoute.SPEAKERPHONE,12))
  routes.confirm(restored,RouteObservation(CallRoute.SPEAKERPHONE,12,21,3,true))
  check(routes.recordPressed(restored))
  routes.environment(visible=false,microphonePermission=true)
  check(!routes.mayOpenMicrophone(speaker))
  routes.environment(visible=true,microphonePermission=true)
  check(!routes.mayOpenMicrophone(speaker))
  val headset=requireNotNull(routes.request(CallRoute.HEADSET,13))
  check(routes.confirm(headset,RouteObservation(CallRoute.HEADSET,13,22,3,true)))
  check(routes.recordPressed(headset) && routes.setLiveMonitor(headset,true))
  check(routes.snapshot().liveMonitor)
  routes.routeLost()
  check(!routes.snapshot().liveMonitor && !routes.acceptCapturedBlock(headset,3))
  val newSpeaker=requireNotNull(routes.request(CallRoute.SPEAKERPHONE,12))
  routes.confirm(newSpeaker,RouteObservation(CallRoute.SPEAKERPHONE,12,21,4,true))
  routes.recordPressed(newSpeaker)
  check(!routes.setLiveMonitor(newSpeaker,true))
  routes.revoke()
  check(routes.request(CallRoute.HANDSET,11)==null && !routes.recordPressed(newSpeaker))
  check(!routes.confirm(newSpeaker,RouteObservation(CallRoute.SPEAKERPHONE,12,21,4,true)))
  check(invalidations>=5)
  println("PASS route request/confirmation, explicit microphone consent, stale acknowledgments, shared input IDs, segment isolation, privacy/background and headset-loss monitoring gates")
}
