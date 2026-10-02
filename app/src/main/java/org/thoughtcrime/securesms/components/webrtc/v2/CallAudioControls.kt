/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.webrtc.audio.CallDenoiseController
import org.thoughtcrime.securesms.webrtc.audio.DenoiseStatus
import org.thoughtcrime.securesms.webrtc.audio.Direction
import org.thoughtcrime.securesms.webrtc.audio.IncomingAudioController

@Composable
fun CallAudioControls(onSheetDisplayChanged: (Boolean) -> Unit) {
  val context = LocalContext.current
  var visible by rememberSaveable { mutableStateOf(false) }
  val sheetChanged by rememberUpdatedState(onSheetDisplayChanged)
  LaunchedEffect(context) { IncomingAudioController.initialize(context) }
  DisposableEffect(visible) {
    if (visible) sheetChanged(true)
    onDispose {
      if (visible) {
        IncomingAudioController.save()
        CallDenoiseController.save()
        sheetChanged(false)
      }
    }
  }
  OutlinedButton(onClick = { visible = true }) {
    Icon(painterResource(R.drawable.ic_incoming_audio_24), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.call_audio_title))
  }
  if (visible) CallAudioSheet { visible = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallAudioSheet(onDismiss: () -> Unit) {
  val ui by CallDenoiseController.state.collectAsState()
  val incoming by IncomingAudioController.settings.collectAsState()
  var selected by rememberSaveable { mutableStateOf(0) }
  var receivedStatus by remember { mutableStateOf(DenoiseStatus()) }
  var sentStatus by remember { mutableStateOf(DenoiseStatus()) }
  val receivedScroll = rememberScrollState()
  val sentScroll = rememberScrollState()
  LaunchedEffect(Unit) {
    while (isActive) {
      val pair = withContext(Dispatchers.Default) {
        CallDenoiseController.readStatus(Direction.RECEIVED) to CallDenoiseController.readStatus(Direction.SENT)
      }
      receivedStatus = pair.first
      sentStatus = pair.second
      delay(100)
    }
  }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.call_audio_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.incoming_audio_close)) }
      }
      Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.denoise_received_summary, denoiseStatusLabel(ui.settings.received, ui, receivedStatus)), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.denoise_sent_summary, denoiseStatusLabel(ui.settings.sent, ui, sentStatus)), style = MaterialTheme.typography.labelMedium)
      }
      TabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.surface) {
        Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text(stringResource(R.string.denoise_received)) })
        Tab(selected = selected == 1, onClick = { selected = 1 }, text = { Text(stringResource(R.string.denoise_sent)) })
      }
      val direction = if (selected == 0) Direction.RECEIVED else Direction.SENT
      Column(
        Modifier.weight(1f).verticalScroll(if (selected == 0) receivedScroll else sentScroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
      ) {
        if (!ui.storageAvailable) Text(stringResource(R.string.denoise_storage_unavailable), color = MaterialTheme.colorScheme.error)
        DeepFilterSection(direction, ui.settings[direction], ui, if (selected == 0) receivedStatus else sentStatus,
          onUpdate = { settings, persist -> CallDenoiseController.update(direction, settings, persist) },
          onSave = { CallDenoiseController.save() },
          onBypass = { CallDenoiseController.setBypassed(direction, it) },
          onRetry = { CallDenoiseController.retry(direction) },
          onReset = { CallDenoiseController.reset(direction) })
        if (direction == Direction.RECEIVED) ReceivedAudioEffects(incoming)
        Text(stringResource(R.string.denoise_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}
