/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioBridge
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioController
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioSettings
import java.util.Locale
import kotlin.math.round

/** Separate pill above the existing controls: no crowding the hang-up/mic row. */
@Composable
fun IncomingAudioControls(onSheetDisplayChanged: (Boolean) -> Unit) {
  val context = LocalContext.current
  val settings by IncomingAudioController.settings.collectAsState()
  var visible by rememberSaveable { mutableStateOf(false) }
  val sheetChanged by rememberUpdatedState(onSheetDisplayChanged)
  LaunchedEffect(context) { IncomingAudioController.initialize(context) }
  DisposableEffect(visible) {
    if (visible) sheetChanged(true)
    onDispose {
      if (visible) {
        IncomingAudioController.save()
        sheetChanged(false)
      }
    }
  }
  OutlinedButton(onClick = { visible = true }) {
    Icon(painterResource(R.drawable.ic_incoming_audio_24), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.incoming_audio_title))
    Spacer(Modifier.width(12.dp))
    Text(stringResource(if (settings.enabled && IncomingAudioController.available) R.string.incoming_audio_on else R.string.incoming_audio_off), style = MaterialTheme.typography.labelSmall)
  }
  if (visible) IncomingAudioSheet(settings = settings, onDismiss = { visible = false })
}

private data class AudioMeterState(
  val input: Float = -120f, val output: Float = -120f,
  val compression: Float = 0f, val limiting: Float = 0f,
  val rate: Int = 0, val channels: Int = 0, val active: Boolean = false,
  val available: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncomingAudioSheet(settings: IncomingAudioSettings, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var eqExpanded by rememberSaveable { mutableStateOf(false) }
  var advanced by rememberSaveable { mutableStateOf(false) }
  var meter by remember { mutableStateOf(AudioMeterState(available = IncomingAudioController.available)) }
  LaunchedEffect(Unit) {
    val values = FloatArray(6)
    var previous = IncomingAudioBridge.readMeters(values)
    var changedAt = 0L
    while (isActive) {
      delay(100)
      val count = IncomingAudioBridge.readMeters(values)
      val now = SystemClock.elapsedRealtime()
      if (count >= 0 && count != previous) changedAt = now
      previous = count
      meter = AudioMeterState(values[0], values[1], values[2], values[3], values[4].toInt(), values[5].toInt(), count >= 0 && changedAt != 0L && now - changedAt < 1500, IncomingAudioController.available)
    }
  }
  val available = meter.available
  val edit = available && settings.enabled
  fun update(value: IncomingAudioSettings, persist: Boolean = true) = IncomingAudioController.update(value, persist)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.incoming_audio_title), style = MaterialTheme.typography.headlineSmall)
          Text(stringResource(R.string.incoming_audio_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.incoming_audio_close)) }
      }
      if (!available) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
          Text(stringResource(R.string.incoming_audio_unavailable), modifier = Modifier.padding(16.dp))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.incoming_audio_enable), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = settings.enabled, enabled = available, onCheckedChange = { update(settings.copy(enabled = it)) })
      }
      if (available) {
        Text(if (meter.active) stringResource(R.string.incoming_audio_active, meter.rate, meter.channels) else stringResource(R.string.incoming_audio_waiting), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          LevelMeter(stringResource(R.string.incoming_audio_input), meter.input, meter.active, Modifier.weight(1f))
          LevelMeter(stringResource(R.string.incoming_audio_output), meter.output, meter.active, Modifier.weight(1f))
        }
        Text(stringResource(R.string.incoming_audio_reduction, number(if (meter.active) meter.compression else 0f), number(if (meter.active) meter.limiting else 0f)), style = MaterialTheme.typography.bodySmall)
      }
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(enabled = available, onClick = { update(IncomingAudioSettings(enabled = true, gain = 2f, makeup = 2f, eq = listOf(-3f, -3f, -2f, -1f, 0f, 1f, 2f, 1f, 0f, 0f))) }, label = { Text(stringResource(R.string.incoming_audio_preset_clear)) })
        AssistChip(enabled = available, onClick = { update(IncomingAudioSettings(enabled = true, gain = 6f, makeup = 3f)) }, label = { Text(stringResource(R.string.incoming_audio_preset_quiet)) })
        AssistChip(enabled = available, onClick = { update(IncomingAudioSettings(enabled = true, threshold = -18f, ratio = 2f, makeup = 2f)) }, label = { Text(stringResource(R.string.incoming_audio_preset_gentle)) })
      }
      AudioSection(stringResource(R.string.incoming_audio_gain), settings.gainEnabled, edit, { update(settings.copy(gainEnabled = it)) }) {
        AudioSlider(stringResource(R.string.incoming_audio_gain), settings.gain, -24f..18f, "dB", edit && settings.gainEnabled) { update(settings.copy(gain = it), false) }
      }
      AudioSection(stringResource(R.string.incoming_audio_eq), settings.eqEnabled, edit, { update(settings.copy(eqEnabled = it)) }) {
        Row {
          TextButton(onClick = { eqExpanded = !eqExpanded }) { Text(stringResource(if (eqExpanded) R.string.incoming_audio_eq_hide else R.string.incoming_audio_eq_show)) }
          TextButton(enabled = edit, onClick = { update(settings.copy(eq = List(10) { 0f })) }) { Text(stringResource(R.string.incoming_audio_eq_flat)) }
        }
        if (eqExpanded) {
          val frequencies = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
          frequencies.forEachIndexed { index, frequency ->
            val supported = !meter.active || frequency < meter.rate * 0.45f
            val label = if (frequency >= 1000) "${number(frequency / 1000)} kHz" else "${number(frequency)} Hz"
            AudioSlider(label, settings.eq[index], -12f..12f, "dB", edit && settings.eqEnabled && supported) { value -> update(settings.copy(eq = settings.eq.mapIndexed { i, old -> if (i == index) value else old }), false) }
            if (!supported) Text(stringResource(R.string.incoming_audio_high_band), style = MaterialTheme.typography.labelSmall)
          }
        }
      }
      AudioSection(stringResource(R.string.incoming_audio_compressor), settings.compressorEnabled, edit, { update(settings.copy(compressorEnabled = it)) }) {
        val enabled = edit && settings.compressorEnabled
        AudioSlider(stringResource(R.string.incoming_audio_threshold), settings.threshold, -60f..0f, "dBFS", enabled) { update(settings.copy(threshold = it), false) }
        AudioSlider(stringResource(R.string.incoming_audio_ratio), settings.ratio, 1f..20f, ":1", enabled) { update(settings.copy(ratio = it), false) }
        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.incoming_audio_simple else R.string.incoming_audio_advanced)) }
        if (advanced) {
          AudioSlider(stringResource(R.string.incoming_audio_attack), settings.attack, 0.1f..100f, "ms", enabled) { update(settings.copy(attack = it), false) }
          AudioSlider(stringResource(R.string.incoming_audio_release), settings.release, 10f..1000f, "ms", enabled) { update(settings.copy(release = it), false) }
          AudioSlider(stringResource(R.string.incoming_audio_knee), settings.knee, 0f..24f, "dB", enabled) { update(settings.copy(knee = it), false) }
          AudioSlider(stringResource(R.string.incoming_audio_makeup), settings.makeup, 0f..18f, "dB", enabled) { update(settings.copy(makeup = it), false) }
        }
      }
      AudioSection(stringResource(R.string.incoming_audio_limiter), settings.limiterEnabled, edit, { update(settings.copy(limiterEnabled = it)) }) {
        val enabled = edit && settings.limiterEnabled
        AudioSlider(stringResource(R.string.incoming_audio_ceiling), settings.ceiling, -24f..-0.1f, "dBFS", enabled) { update(settings.copy(ceiling = it), false) }
        AudioSlider(stringResource(R.string.incoming_audio_release), settings.limiterRelease, 10f..1000f, "ms", enabled) { update(settings.copy(limiterRelease = it), false) }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = available && settings.enabled, onClick = { update(settings.copy(enabled = false)) }) { Text(stringResource(R.string.incoming_audio_bypass)) }
        TextButton(onClick = { update(IncomingAudioSettings()) }) { Text(stringResource(R.string.incoming_audio_reset)) }
      }
      Text(stringResource(R.string.incoming_audio_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun AudioSection(title: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, content: @Composable () -> Unit) {
  Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
      }
      content()
    }
  }
}

@Composable
private fun AudioSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, enabled: Boolean, onValue: (Float) -> Unit) {
  Column {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text("${number(value)} $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value.coerceIn(range), valueRange = range, enabled = enabled, onValueChange = { onValue((round(it * 10) / 10).coerceIn(range)) }, onValueChangeFinished = IncomingAudioController::save)
  }
}

@Composable
private fun LevelMeter(label: String, value: Float, active: Boolean, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(if (active) "$label  ${number(value)} dBFS" else "$label  —", style = MaterialTheme.typography.labelSmall)
    LinearProgressIndicator(progress = { if (active) ((value + 60) / 60).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
  }
}

private fun number(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
