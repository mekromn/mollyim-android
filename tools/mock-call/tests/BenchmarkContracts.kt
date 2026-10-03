/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import org.thoughtcrime.securesms.webrtc.audio.Model

fun main() {
  val results = listOf(
    DenoiseBenchmarkResult(Model.STANDARD, meanMs=6.0f, p95Ms=8.0f, misses=0, processed=250, snr=8f, fallbackOccurred=false, complete=true),
    DenoiseBenchmarkResult(Model.MOBILE_FUSED, meanMs=3.0f, p95Ms=4.5f, misses=0, processed=250, snr=8f, fallbackOccurred=false, complete=true),
    DenoiseBenchmarkResult(Model.LOW_LATENCY, meanMs=9.0f, p95Ms=12.0f, misses=7, processed=250, snr=8f, fallbackOccurred=true, complete=true)
  )
  val summary = DenoiseBenchmarkJudge.summarize(results)
  check(summary.realtimePick == Model.MOBILE_FUSED)
  check(summary.results.size == 3)
  check(summary.results.single { it.model == Model.LOW_LATENCY }.rtfP95 == 1.2f)

  val incompleteFast = DenoiseBenchmarkResult(Model.STANDARD, 1f, 1f, 0, 20, 0f, false, false)
  val completeSlower = DenoiseBenchmarkResult(Model.MOBILE_FUSED, 4f, 5f, 0, 220, 0f, false, true)
  check(DenoiseBenchmarkJudge.summarize(listOf(incompleteFast, completeSlower)).realtimePick == Model.MOBILE_FUSED)

  val missed = DenoiseBenchmarkResult(Model.STANDARD, 2f, 3f, 2, 220, 0f, true, true)
  val clean = DenoiseBenchmarkResult(Model.MOBILE_FUSED, 4f, 5f, 0, 220, 0f, false, true)
  check(DenoiseBenchmarkJudge.summarize(listOf(missed, clean)).realtimePick == Model.MOBILE_FUSED)

  check(DenoiseBenchmarkJudge.summarize(listOf(incompleteFast)).realtimePick == null)
  println("PASS benchmark judge: complete stable realtime model selection and RTF")
}
