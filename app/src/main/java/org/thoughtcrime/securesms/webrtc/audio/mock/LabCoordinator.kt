/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class ApplyResult(val applied: Boolean, val revision: Long = 0, val reason: String = "")
/** No microphone/file/Android API here. Real call reservation and Apply share one lock.
 * Revocation closes native gates immediately; the returned future covers device stop,
 * not model destruction or file export. Late completions cannot acquire an old token.
 */
class LabCoordinator(
  private val readProduction: () -> LabConfiguration,
  private val publishProduction: (LabConfiguration) -> Unit,
  private val revokeNative: (LabToken) -> CompletionStage<Void>
) {
  private var next = 1L
  private var active: LabToken? = null
  private var initial: LabConfiguration? = null
  private var realReserved = false
  private var revision = 0L
  private var retiring: CompletionStage<Void> = CompletableFuture.completedFuture(null)

  @Synchronized fun open(): LabToken? {
    if (realReserved || active != null || next == Long.MAX_VALUE || (!retiring.toCompletableFuture().isDone || retiring.toCompletableFuture().isCompletedExceptionally)) return null
    val snapshot = readProduction()
    if (!snapshot.valid()) return null
    val token = LabToken(next++, 1)
    active = token
    initial = snapshot.copy(effects = snapshot.effects.copy(eq = snapshot.effects.eq.toList()))
    return token
  }
  @Synchronized fun check(token: LabToken): Boolean = !realReserved && active == token
  @Synchronized fun initialConfiguration(token: LabToken): LabConfiguration? = initial.takeIf { check(token) }
  @Synchronized fun close(token: LabToken) {
    if (active != token) return
    active = null
    initial = null
    retiring = revokeNative(token)
  }
  @Synchronized fun preemptForRealCall(): CompletionStage<Void> {
    realReserved = true
    val old = active
    active = null
    initial = null
    if (old != null) retiring = revokeNative(old)
    return retiring
  }
  /** Only the authoritative real-call state owner releases this reservation. */
  @Synchronized fun realCallFinished() { realReserved = false }
  @Synchronized fun applyToCalls(token: LabToken, value: LabConfiguration, sections: Set<LabSection>): ApplyResult {
    if (!check(token)) return ApplyResult(false, reason = "Local session is no longer active")
    if (!value.valid() || sections.isEmpty()) return ApplyResult(false, reason = "Invalid or empty configuration")
    val selected = readProduction().selectedFrom(value, sections)
    if (!selected.valid()) return ApplyResult(false, reason = "Invalid production configuration")
    return try {
      publishProduction(selected)
      revision++
      ApplyResult(true, revision)
    } catch (_: Exception) {
      ApplyResult(false, reason = "Settings could not be saved")
    }
  }
}
