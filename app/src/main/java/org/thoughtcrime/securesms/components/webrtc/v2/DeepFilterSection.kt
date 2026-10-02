/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.webrtc.audio.DenoiseSettings
import org.thoughtcrime.securesms.webrtc.audio.DenoiseStatus
import org.thoughtcrime.securesms.webrtc.audio.DenoiseUiSnapshot
import org.thoughtcrime.securesms.webrtc.audio.Direction
import org.thoughtcrime.securesms.webrtc.audio.Model
import org.thoughtcrime.securesms.webrtc.audio.Preset
import java.util.Locale
import kotlin.math.log10
import kotlin.math.round

@Composable
internal fun denoiseStatusLabel(settings: DenoiseSettings, ui: DenoiseUiSnapshot, status: DenoiseStatus): String = stringResource(
  when {
    status.effective == 6 -> R.string.denoise_muted
    !settings.enabled -> R.string.denoise_off
    !ui.nativeAvailable -> R.string.denoise_unavailable
    ui.assetsLoading -> R.string.denoise_loading
    ui.assetsFailed -> R.string.denoise_assets_unavailable
    else -> when (status.effective) {
      0 -> R.string.denoise_off
      1 -> R.string.denoise_loading
      2 -> R.string.denoise_active
      3 -> R.string.denoise_manual_bypass
      5 -> R.string.denoise_overloaded
      7 -> R.string.denoise_unsupported
      8 -> R.string.denoise_zero
      else -> R.string.denoise_unavailable
    }
  }
)

@Composable
fun DeepFilterSection(
  direction: Direction,
  settings: DenoiseSettings,
  ui: DenoiseUiSnapshot,
  status: DenoiseStatus,
  onUpdate: (DenoiseSettings, Boolean) -> Unit,
  onSave: () -> Unit,
  onBypass: (Boolean) -> Unit,
  onRetry: () -> Unit,
  onReset: () -> Unit
) {
  var advanced by rememberSaveable(direction) { mutableStateOf(false) }
  val prefix = "denoise-${direction.name.lowercase(Locale.ROOT)}"
  val enableLabel = stringResource(R.string.denoise_enable)
  Surface(
    shape = RoundedCornerShape(20.dp),
    border = if (SignalTheme.isAmoledBlack) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.denoise_title), style = MaterialTheme.typography.titleLarge)
          Text(denoiseStatusLabel(settings, ui, status), style = MaterialTheme.typography.labelMedium)
        }
        Switch(checked = settings.enabled, onCheckedChange = { onUpdate(settings.copy(enabled = it), true) },
          modifier = Modifier.testTag("$prefix-enable").semantics { contentDescription = enableLabel })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Model.entries.forEach { model ->
          FilterChip(selected = settings.model == model, onClick = { onUpdate(settings.copy(model = model), true) },
            label = { Text(stringResource(if (model == Model.STANDARD) R.string.denoise_standard else R.string.denoise_low_latency)) },
            modifier = Modifier.testTag("$prefix-model-${model.wireId}"))
        }
      }
      Text(stringResource(R.string.denoise_preset, stringResource(when (settings.selectedPreset) {
        Preset.GENTLE -> R.string.denoise_gentle
        Preset.BALANCED -> R.string.denoise_balanced
        Preset.STRONG -> R.string.denoise_strong
        Preset.CUSTOM -> R.string.denoise_custom
      })), style = MaterialTheme.typography.labelMedium)
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Preset.GENTLE, Preset.BALANCED, Preset.STRONG).forEach { preset ->
          FilterChip(selected = settings.selectedPreset == preset, onClick = { onUpdate(settings.preset(preset), true) },
            modifier = Modifier.testTag("$prefix-preset-${preset.name.lowercase(Locale.ROOT)}"),
            label = { Text(stringResource(when (preset) { Preset.GENTLE -> R.string.denoise_gentle; Preset.BALANCED -> R.string.denoise_balanced; else -> R.string.denoise_strong })) })
        }
      }
      DenoiseSlider(stringResource(R.string.denoise_attenuation), settings.attenuationDb, 0f..100f, 0, "dB", "$prefix-attenuation", onSave) {
        onUpdate(settings.copy(attenuationDb = it), false)
      }
      Text(stringResource(R.string.denoise_limit_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      val postLabel = stringResource(R.string.denoise_post_filter)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(postLabel, modifier = Modifier.weight(1f))
        Switch(checked = settings.postFilter, onCheckedChange = { onUpdate(settings.copy(postFilter = it), true) },
          modifier = Modifier.testTag("$prefix-post").semantics { contentDescription = postLabel })
      }
      DenoiseSlider(stringResource(R.string.denoise_post_strength), settings.beta, 0f..0.05f, 3, "", "$prefix-beta", onSave) {
        onUpdate(settings.copy(beta = it), false)
      }
      TextButton(onClick = { advanced = !advanced }) {
        Text(stringResource(if (advanced) R.string.denoise_hide_advanced else R.string.denoise_advanced))
      }
      if (advanced) {
        DenoiseSlider(stringResource(R.string.denoise_min_snr), settings.minSnr, -30f..60f, 0, "dB", "$prefix-min", onSave) { onUpdate(settings.copy(minSnr = it), false) }
        DenoiseSlider(stringResource(R.string.denoise_df_snr), settings.dfSnr, -30f..60f, 0, "dB", "$prefix-df", onSave) { onUpdate(settings.copy(dfSnr = it), false) }
        DenoiseSlider(stringResource(R.string.denoise_erb_snr), settings.erbSnr, -30f..60f, 0, "dB", "$prefix-erb", onSave) { onUpdate(settings.copy(erbSnr = it), false) }
        Text(stringResource(R.string.denoise_threshold_note), style = MaterialTheme.typography.bodySmall)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DenoiseMeter(stringResource(R.string.denoise_input), status.inputPeak, status.levelsValid, Modifier.weight(1f))
        DenoiseMeter(stringResource(R.string.denoise_output), status.outputPeak, status.levelsValid, Modifier.weight(1f))
      }
      if (status.rate > 0) Text(stringResource(R.string.denoise_stream, status.rate, status.channels, denoiseNumber(status.delayMs, 1)), style = MaterialTheme.typography.bodySmall)
      if (status.inferenceValid) {
        Text(stringResource(R.string.denoise_timing, denoiseNumber(status.meanUs / 1000f, 2), denoiseNumber(status.p95Us / 1000f, 2)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.denoise_snr, denoiseNumber(status.snr, 1), status.misses), style = MaterialTheme.typography.bodySmall)
      } else Text(stringResource(R.string.denoise_no_metrics), style = MaterialTheme.typography.bodySmall)
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = settings.enabled && ui.nativeAvailable, onClick = { onBypass(!status.bypassed) }) {
          Text(stringResource(if (status.bypassed) R.string.denoise_resume else R.string.denoise_bypass))
        }
        TextButton(enabled = settings.enabled, onClick = onRetry) { Text(stringResource(R.string.denoise_retry)) }
        TextButton(onClick = onReset, modifier = Modifier.testTag("$prefix-reset")) { Text(stringResource(R.string.denoise_reset)) }
      }
      Text(stringResource(R.string.denoise_bypass_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun DenoiseSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, decimals: Int, unit: String, tag: String, onSave: () -> Unit, onValue: (Float) -> Unit) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text("${denoiseNumber(value, decimals)} $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value.coerceIn(range), valueRange = range,
      onValueChange = { onValue(if (decimals == 3) round(it * 1000f) / 1000f else round(it)) },
      onValueChangeFinished = onSave, modifier = Modifier.testTag(tag).semantics { contentDescription = label })
  }
}

@Composable
private fun DenoiseMeter(label: String, value: Float, valid: Boolean, modifier: Modifier) {
  val db = if (valid && value > 0f) (20f * log10(value)).coerceAtLeast(-120f) else -120f
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(if (valid) "$label ${denoiseNumber(db, 1)} dBFS" else "$label —", style = MaterialTheme.typography.labelSmall)
    LinearProgressIndicator(progress = { if (valid) ((db + 60f) / 60f).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
  }
}
private fun denoiseNumber(value: Float, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", value)
