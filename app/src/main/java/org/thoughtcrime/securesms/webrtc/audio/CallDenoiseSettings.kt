/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import kotlin.math.round

/** Wire values are stable ABI, not enum ordinals. */
enum class Direction(val wireId: Int) { RECEIVED(0), SENT(1) }
enum class Model(val wireId: Int, val asset: String) {
  STANDARD(0, "DeepFilterNet3_onnx.tar.gz"),
  LOW_LATENCY(1, "DeepFilterNet3_ll_onnx.tar.gz"),
  MOBILE_FUSED(2, "DeepFilterNet3_onnx_mobile.tar.gz")
}
enum class Preset { GENTLE, BALANCED, STRONG, CUSTOM }

data class DenoiseSettings(
  val enabled: Boolean = false,
  val model: Model = Model.STANDARD,
  val attenuationDb: Float = 30f,
  val postFilter: Boolean = false,
  val beta: Float = 0.02f,
  val minSnr: Float = -10f,
  val erbSnr: Float = 30f,
  val dfSnr: Float = 20f
) {
  fun sanitized(): DenoiseSettings {
    fun bounded(value: Float, default: Float, low: Float, high: Float, steps: Float = 1f): Float =
      (round((if (value.isFinite()) value else default).coerceIn(low, high) * steps) / steps).coerceIn(low, high)
    val minimum = bounded(minSnr, -10f, -30f, 60f)
    val deepFilter = bounded(dfSnr, 20f, minimum, 60f)
    return copy(
      attenuationDb = bounded(attenuationDb, 30f, 0f, 100f),
      beta = bounded(beta, 0.02f, 0f, 0.05f, 1000f),
      minSnr = minimum,
      dfSnr = deepFilter,
      erbSnr = bounded(erbSnr, 30f, deepFilter, 60f)
    )
  }

  /** Presets never enable an effect or change another direction, model or thresholds. */
  fun preset(preset: Preset): DenoiseSettings = when (preset) {
    Preset.GENTLE -> copy(attenuationDb = 12f, postFilter = false)
    Preset.BALANCED -> copy(attenuationDb = 30f, postFilter = false)
    Preset.STRONG -> copy(attenuationDb = 100f, postFilter = true, beta = 0.02f)
    Preset.CUSTOM -> this
  }

  val selectedPreset: Preset get() = when {
    attenuationDb == 12f && !postFilter -> Preset.GENTLE
    attenuationDb == 30f && !postFilter -> Preset.BALANCED
    attenuationDb == 100f && postFilter && beta == 0.02f -> Preset.STRONG
    else -> Preset.CUSTOM
  }

  fun toStored(): Map<String, String> = sanitized().let {
    mapOf("version" to "1", "enabled" to it.enabled.toString(), "model" to it.model.wireId.toString(),
      "attenuationDb" to it.attenuationDb.toString(), "postFilter" to it.postFilter.toString(),
      "beta" to it.beta.toString(), "minSnr" to it.minSnr.toString(), "erbSnr" to it.erbSnr.toString(), "dfSnr" to it.dfSnr.toString())
  }

  companion object {
    /** Corrupt or future stored settings restore only this direction's disabled defaults. */
    fun fromStored(values: Map<String, String>): DenoiseSettings {
      if (values.isEmpty()) return DenoiseSettings()
      return try {
        require(values["version"] == "1")
        fun boolean(key: String) = when (values[key]) { "true" -> true; "false" -> false; else -> error("Invalid setting") }
        fun number(key: String, range: ClosedFloatingPointRange<Float>): Float =
          requireNotNull(values[key]?.toFloatOrNull()).also { require(it.isFinite() && it in range) }
        val value = DenoiseSettings(
          enabled = boolean("enabled"),
          model = Model.entries.first { it.wireId.toString() == values["model"] },
          attenuationDb = number("attenuationDb", 0f..100f), postFilter = boolean("postFilter"),
          beta = number("beta", 0f..0.05f), minSnr = number("minSnr", -30f..60f),
          erbSnr = number("erbSnr", -30f..60f), dfSnr = number("dfSnr", -30f..60f)
        )
        require(value.minSnr <= value.dfSnr && value.dfSnr <= value.erbSnr)
        value.sanitized()
      } catch (_: IllegalArgumentException) { DenoiseSettings() }
        catch (_: IllegalStateException) { DenoiseSettings() }
        catch (_: NoSuchElementException) { DenoiseSettings() }
    }
  }
}

data class DenoisePair(val received: DenoiseSettings = DenoiseSettings(), val sent: DenoiseSettings = DenoiseSettings()) {
  operator fun get(direction: Direction): DenoiseSettings = if (direction == Direction.RECEIVED) received else sent
  fun updated(direction: Direction, value: DenoiseSettings): DenoisePair =
    if (direction == Direction.RECEIVED) copy(received = value.sanitized()) else copy(sent = value.sanitized())
}
