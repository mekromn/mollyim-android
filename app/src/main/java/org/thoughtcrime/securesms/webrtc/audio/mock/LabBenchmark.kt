/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import org.thoughtcrime.securesms.webrtc.audio.Model

data class DenoiseBenchmarkResult(
  val model: Model,
  val meanMs: Float,
  val p95Ms: Float,
  val misses: Long,
  val processed: Long,
  val snr: Float,
  val fallbackOccurred: Boolean,
  val complete: Boolean
) {
  val rtfP95: Float
    get() = p95Ms / 10f

  val realtimeSafe: Boolean
    get() = complete &&
      processed >= 100 &&
      meanMs.isFinite() && p95Ms.isFinite() &&
      meanMs >= 0f && p95Ms >= 0f &&
      misses == 0L &&
      !fallbackOccurred &&
      p95Ms < 10f
}

data class DenoiseBenchmarkSummary(
  val results: List<DenoiseBenchmarkResult> = emptyList(),
  val realtimePick: Model? = null
)

object DenoiseBenchmarkJudge {
  fun summarize(results: List<DenoiseBenchmarkResult>): DenoiseBenchmarkSummary {
    val stable = results
      .filter { it.realtimeSafe }
      .sortedWith(compareBy<DenoiseBenchmarkResult> { it.p95Ms }.thenBy { it.meanMs })

    return DenoiseBenchmarkSummary(
      results = results.toList(),
      realtimePick = stable.firstOrNull()?.model
    )
  }
}
