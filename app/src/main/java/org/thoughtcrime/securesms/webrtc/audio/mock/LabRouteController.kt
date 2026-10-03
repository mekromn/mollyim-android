/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

/** HANDSET is a communication earpiece route, never Android MODE_NORMAL. */
enum class CallRoute { HANDSET, SPEAKERPHONE, HEADSET }
data class RouteTicket(val owner: LabToken, val request: Long, val segment: Long)
data class RouteObservation(
  val output: CallRoute,
  val outputDeviceId: Int,
  val inputDeviceId: Int?,
  val streamGeneration: Long,
  val captureRunning: Boolean
)
data class LabRouteSnapshot(
  val ticket: RouteTicket? = null,
  val requested: CallRoute? = null,
  val requestedDeviceId: Int? = null,
  val actual: RouteObservation? = null,
  val outputConfirmed: Boolean = false,
  val captureRequested: Boolean = false,
  val liveMonitor: Boolean = false,
  val visible: Boolean = false,
  val microphonePermission: Boolean = false,
  val revoked: Boolean = false,
  val segment: Long = 0
)

/** Pure route/consent state. Android and backend observations remain separate.
 * The invalidation callback only closes gates; it must not block on hardware,
 * models or IO. Hardware commands must recheck their ticket at execution time.
 */
class LabRouteController(private val owner: LabToken, private val invalidateCapture: () -> Unit) {
  private var state = LabRouteSnapshot()
  private var sequence = 0L

  @Synchronized fun snapshot(): LabRouteSnapshot = state

  @Synchronized fun environment(visible: Boolean, microphonePermission: Boolean) {
    if (state.revoked) return
    if ((!visible && state.visible) || (!microphonePermission && state.microphonePermission)) {
      invalidateCapture()
      // Retire the ticket too: a delayed route/capture acknowledgment cannot
      // revive permission from a previous foreground or microphone epoch.
      state = state.copy(ticket = null, actual = null, outputConfirmed = false, captureRequested = false, liveMonitor = false)
    }
    state = state.copy(visible = visible, microphonePermission = microphonePermission)
  }

  @Synchronized fun request(route: CallRoute, outputDeviceId: Int): RouteTicket? {
    if (state.revoked || !state.visible || outputDeviceId <= 0 || sequence == Long.MAX_VALUE || state.segment == Long.MAX_VALUE) return null
    invalidateCapture()
    val next = RouteTicket(owner, ++sequence, state.segment + 1)
    state = state.copy(ticket = next, requested = route, requestedDeviceId = outputDeviceId,
      actual = null, outputConfirmed = false, liveMonitor = false, segment = next.segment)
    return next
  }

  private fun current(ticket: RouteTicket): Boolean = !state.revoked && state.visible && ticket.owner == owner && ticket == state.ticket

  @Synchronized fun confirm(ticket: RouteTicket, observed: RouteObservation): Boolean {
    if (!current(ticket) || observed.output != state.requested || observed.outputDeviceId != state.requestedDeviceId || observed.streamGeneration < 0) return false
    // The same input ID may be reported for different communication routes.
    // Do not infer vendor microphone processing from ID equality.
    state = state.copy(actual = observed, outputConfirmed = true)
    if (state.liveMonitor && observed.output != CallRoute.HEADSET) {
      invalidateCapture()
      state = state.copy(liveMonitor = false)
    }
    return true
  }

  @Synchronized fun recordPressed(ticket: RouteTicket): Boolean {
    if (!current(ticket) || !state.microphonePermission || !state.outputConfirmed) return false
    state = state.copy(captureRequested = true)
    return true
  }

  @Synchronized fun mayOpenMicrophone(ticket: RouteTicket): Boolean =
    current(ticket) && state.microphonePermission && state.outputConfirmed && state.captureRequested

  @Synchronized fun acceptCapturedBlock(ticket: RouteTicket, streamGeneration: Long): Boolean =
    mayOpenMicrophone(ticket) && streamGeneration > 0 && state.actual?.captureRunning == true && state.actual?.streamGeneration == streamGeneration

  @Synchronized fun setLiveMonitor(ticket: RouteTicket, enabled: Boolean): Boolean {
    if (!current(ticket)) return false
    if (enabled && (!mayOpenMicrophone(ticket) || state.actual?.output != CallRoute.HEADSET || state.actual?.captureRunning != true)) return false
    state = state.copy(liveMonitor = enabled)
    return true
  }

  @Synchronized fun stopCapture() {
    if (state.revoked) return
    invalidateCapture()
    state = state.copy(captureRequested = false, liveMonitor = false, actual = state.actual?.copy(captureRunning = false))
  }

  @Synchronized fun routeLost() {
    if (state.revoked) return
    invalidateCapture()
    state = state.copy(ticket = null, actual = null, outputConfirmed = false, liveMonitor = false, captureRequested = false)
  }

  @Synchronized fun revoke() {
    if (state.revoked) return
    invalidateCapture()
    state = state.copy(revoked = true, ticket = null, actual = null, outputConfirmed = false, captureRequested = false, liveMonitor = false)
  }
}
