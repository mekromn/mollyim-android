/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.components.webrtc.WebRtcAudioDevice
import org.thoughtcrime.securesms.components.webrtc.WebRtcAudioOutput
import org.thoughtcrime.securesms.components.webrtc.WebRtcLocalRenderState
import org.thoughtcrime.securesms.events.CallParticipant
import org.thoughtcrime.securesms.events.CallParticipantId
import org.thoughtcrime.securesms.events.WebRtcViewModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.webrtc.audio.Direction
import org.thoughtcrime.securesms.webrtc.audio.Model
import org.thoughtcrime.securesms.webrtc.audio.mock.*
import java.util.Locale

/**
 * The lab now renders the production CallScreen and production CallControls.
 * Only local-test transport/diagnostic chrome is overlaid. No contact/history
 * row or network call is created by the ephemeral display participants.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MockCallScreen(
  controller: MockCallLabController,
  onExit: () -> Unit,
  onRecord: (LabMode) -> Unit,
  onImport: () -> Unit,
  onExportTrack: (LabTake, Int) -> Unit,
  onExportReport: (Boolean) -> Unit
) {
  val view by controller.view.collectAsState()
  var diagnostics by rememberSaveable { mutableStateOf(false) }
  var sourcesFor by remember { mutableStateOf<Direction?>(null) }
  var takes by rememberSaveable { mutableStateOf(false) }
  var apply by rememberSaveable { mutableStateOf(false) }
  var chosenSections by remember { mutableStateOf(LabSection.entries.toSet()) }
  var processedConfirmation by remember { mutableStateOf<Triple<Direction, LabTake, Int>?>(null) }
  var selection by remember(view.selectionStartMs, view.selectionEndMs) {
    mutableStateOf(view.selectionStartMs.toFloat()..view.selectionEndMs.coerceAtLeast(view.selectionStartMs + 10).toFloat())
  }

  LaunchedEffect(view.visible, view.retired) {
    if (view.visible && !view.retired && view.routes.isEmpty()) {
      controller.prepareRoutes()
    }
  }

  val callRecipient = remember {
    Recipient(isResolving = false, systemContactName = "Mock Call Lab")
  }
  val remoteParticipant = remember(callRecipient) {
    CallParticipant(
      callParticipantId = CallParticipantId(0, RecipientId.from(Long.MAX_VALUE - 31)),
      recipient = callRecipient,
      isMicrophoneEnabled = true
    )
  }
  val localParticipant = remember(view.stats.muted) {
    CallParticipant(
      recipient = Recipient(isResolving = false, isSelf = true),
      isMicrophoneEnabled = !view.stats.muted
    )
  }
  val pagerState = remember(remoteParticipant) {
    CallParticipantsPagerState(
      callParticipants = listOf(remoteParticipant),
      focusedParticipant = remoteParticipant
    )
  }

  val selectedRoute = remember(view.routes, view.requestedRoute, view.actualRoute) {
    view.routes.firstOrNull { it.id == view.requestedRoute }
      ?: view.routes.firstOrNull { it.label == view.actualRoute }
      ?: view.routes.firstOrNull { it.route == CallRoute.HANDSET }
      ?: view.routes.firstOrNull()
  }
  val controlsState = CallControlsState(
    isEarpieceAvailable = view.routes.isEmpty() || view.routes.any { it.route == CallRoute.HANDSET },
    isBluetoothHeadsetAvailable = view.routes.any { it.asWebRtcOutput() == WebRtcAudioOutput.BLUETOOTH_HEADSET },
    isWiredHeadsetAvailable = view.routes.any { it.asWebRtcOutput() == WebRtcAudioOutput.WIRED_HEADSET },
    skipHiddenState = true,
    displayAudioOutputToggle = true,
    audioOutput = selectedRoute?.asWebRtcOutput() ?: WebRtcAudioOutput.HANDSET,
    displayMicToggle = true,
    isMicEnabled = !view.stats.muted,
    displayEndCallButton = true
  )

  val controlsListener = remember(controller, onExit) {
    object : CallScreenControlsListener by CallScreenControlsListener.Empty {
      override fun onAudioOutputChanged(audioOutput: WebRtcAudioOutput) {
        controller.view.value.routes.firstOrNull { it.asWebRtcOutput() == audioOutput }?.let { controller.selectRoute(it.id) }
      }

      override fun onAudioOutputChanged31(audioOutput: WebRtcAudioDevice) {
        val id = audioOutput.deviceId
          ?: controller.view.value.routes.firstOrNull { it.asWebRtcOutput() == audioOutput.webRtcAudioOutput }?.id
        if (id != null) controller.selectRoute(id)
      }

      override fun onMicChanged(isMicEnabled: Boolean) {
        controller.setMuted(!isMicEnabled)
      }

      override fun onEndCallPressed() {
        onExit()
      }
    }
  }

  val screenController = CallScreenController.rememberCallScreenController(
    skipHiddenState = controlsState.skipHiddenState,
    hasMultipleRemoteParticipants = false,
    onControlsToggled = {},
    callControlsState = controlsState,
    callControlsListener = controlsListener
  )

  CallScreen(
    callRecipient = callRecipient,
    webRtcCallState = WebRtcViewModel.State.CALL_CONNECTED,
    isRemoteVideoOffer = false,
    isInPipMode = false,
    callScreenState = CallScreenState(callStatus = "LOCAL TEST — Nobody is connected · ${view.phase}"),
    callControlsState = controlsState,
    callParticipantsPagerState = pagerState,
    callScreenController = screenController,
    callAudioSession = controller,
    callAudioStatsHeader = {
      LabStatsPanel(view, compact = true, onExpand = { diagnostics = true })
    },
    callControlsExtraContent = {
      LabTestTransportControls(
        view = view,
        controller = controller,
        onRecord = onRecord,
        onDiagnostics = { diagnostics = true }
      )
    },
callScreenControlsListener = controlsListener,
    overflowParticipants = emptyList(),
    localParticipant = localParticipant,
    localRenderState = WebRtcLocalRenderState.SMALLER_RECTANGLE,
    callScreenDialogType = CallScreenDialogType.NONE,
    reactions = emptyList(),
    callInfoView = { alpha ->
      LabCallInfoPanel(
        view = view,
        controller = controller,
        selection = selection,
        onSelectionChange = { selection = it },
        onRecord = onRecord,
        onImport = onImport,
        onTakes = { takes = true },
        onSource = { sourcesFor = it },
        onApply = { apply = true },
        modifier = Modifier.alpha(alpha)
      )
    },
    raiseHandSnackbar = {},
    onNavigationClick = onExit,
    onLocalPictureInPictureClicked = {},
    onLocalPictureInPictureFocusClicked = {},
    onControlsToggled = {},
    callParticipantUpdatePopupController = remember { CallParticipantUpdatePopupController() }
  )

  if (diagnostics) {
    LabDiagnosticsSheet(controller, view, { diagnostics = false }, onExportReport)
  }

  sourcesFor?.let { direction ->
    AlertDialog(
      onDismissRequest = { sourcesFor = null },
      title = { Text("Choose ${direction.name.lowercase()} sample") },
      text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
          view.takes.forEach { take ->
            Text(take.label, style = MaterialTheme.typography.titleSmall)
            take.tracks.forEachIndexed { i, track ->
              TextButton(onClick = {
                sourcesFor = null
                if (track.tap in listOf(2, 3, 4)) processedConfirmation = Triple(direction, take, i)
                else controller.selectSource(direction, take.id, i)
              }) {
                Text("${MockCallLabController.tapName(track.tap)} · ${track.rate} Hz / ${track.channels} ch${if (!take.complete) " · INCOMPLETE" else ""}")
              }
            }
          }
          if (view.takes.isEmpty()) Text("Record a microphone sample or import a WAV first.")
        }
      },
      confirmButton = { TextButton(onClick = { sourcesFor = null }) { Text("Close") } }
    )
  }

  processedConfirmation?.let { pending ->
    AlertDialog(
      onDismissRequest = { processedConfirmation = null },
      title = { Text("Use already-processed audio?") },
      text = { Text("This track already includes processing. Applying effects again will process it a second time; the original remains unchanged.") },
      confirmButton = {
        TextButton(onClick = {
          controller.selectSource(pending.first, pending.second.id, pending.third, true)
          processedConfirmation = null
        }) { Text("Use this source") }
      },
      dismissButton = { TextButton(onClick = { processedConfirmation = null }) { Text("Cancel") } }
    )
  }

  if (takes) {
    AlertDialog(
      onDismissRequest = { takes = false },
      title = { Text("Local takes") },
      text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          view.takes.forEach { take ->
            Text("${take.label} — ${if (take.complete) "Complete" else "Incomplete: ${take.reason}"}", style = MaterialTheme.typography.titleSmall)
            Row {
              TextButton(onClick = { controller.saveTake(take.id) }, enabled = !take.saved) { Text(if (take.saved) "Saved" else "Save") }
              TextButton(onClick = { controller.deleteTake(take.id) }) { Text("Delete") }
            }
            take.tracks.forEachIndexed { i, track ->
              TextButton(onClick = { onExportTrack(take, i) }) {
                Text("Export ${MockCallLabController.tapName(track.tap)} (${track.encoding.name})")
              }
            }
          }
          if (view.takes.isEmpty()) Text("No completed or incomplete takes yet.")
        }
      },
      confirmButton = { TextButton(onClick = { takes = false }) { Text("Close") } }
    )
  }

  if (apply) {
    AlertDialog(
      onDismissRequest = { apply = false },
      title = { Text("Apply to ordinary calls?") },
      text = {
        Column {
          Text("Only the selected settings will be saved. Test playback, recordings and monitoring are not applied.")
          LabSection.entries.forEach { section ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Checkbox(
                checked = section in chosenSections,
                onCheckedChange = { checked -> chosenSections = if (checked) chosenSections + section else chosenSections - section }
              )
              Text(
                when (section) {
                  LabSection.RECEIVED_DENOISE -> "Received DeepFilterNet"
                  LabSection.SENT_DENOISE -> "Sent DeepFilterNet"
                  LabSection.RECEIVED_EFFECTS -> "Received EQ, compressor, gain and limiter"
                }
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = chosenSections.isNotEmpty(),
          onClick = {
            controller.applyToCalls(chosenSections)
            apply = false
          }
        ) { Text("Apply") }
      },
      dismissButton = { TextButton(onClick = { apply = false }) { Text("Cancel") } }
    )
  }
}

private fun LabRouteChoice.asWebRtcOutput(): WebRtcAudioOutput {
  return when (route) {
    CallRoute.HANDSET -> WebRtcAudioOutput.HANDSET
    CallRoute.SPEAKERPHONE -> WebRtcAudioOutput.SPEAKER
    CallRoute.HEADSET -> if (label.contains("Bluetooth", ignoreCase = true)) {
      WebRtcAudioOutput.BLUETOOTH_HEADSET
    } else {
      WebRtcAudioOutput.WIRED_HEADSET
    }
  }
}

@Composable
private fun LabTestTransportControls(
  view: MockCallLabController.View,
  controller: MockCallLabController,
  onRecord: (LabMode) -> Unit,
  onDiagnostics: () -> Unit
) {
  val canPlay = when (view.mode) {
    LabMode.RECEIVED -> view.sources[Direction.RECEIVED.wireId] != null
    LabMode.SENT -> view.sources[Direction.SENT.wireId] != null
    LabMode.BOTH -> view.sources[Direction.RECEIVED.wireId] != null && view.sources[Direction.SENT.wireId] != null
  }
  val receivedP95 = if (view.playing && view.stats.received.inferenceValid) {
    String.format(Locale.ROOT, "%.1f", view.stats.received.p95Ms)
  } else {
    "—"
  }
  val sentP95 = if (view.playing && view.stats.sent.inferenceValid) {
    String.format(Locale.ROOT, "%.1f", view.stats.sent.p95Ms)
  } else {
    "—"
  }
  val misses = if (view.playing) view.stats.received.misses + view.stats.sent.misses else 0L

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
  ) {
    Text(
      "${view.actualRoute} · ${view.phase} · Rx p95 ${receivedP95} ms · Tx p95 ${sentP95} ms · misses $misses",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(
        enabled = !view.retired && !view.recording,
        onClick = { onRecord(if (view.mode == LabMode.RECEIVED) LabMode.SENT else view.mode) }
      ) {
        Text(if (view.recording) "Recording" else "Record")
      }
      OutlinedButton(
        enabled = !view.retired && (view.playing || canPlay),
        onClick = { if (view.playing) controller.pause() else controller.play() }
      ) {
        Text(if (view.playing) "Stop" else "Play")
      }
      TextButton(enabled = !view.retired, onClick = onDiagnostics) {
        Text("Stats")
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabCallInfoPanel(
  view: MockCallLabController.View,
  controller: MockCallLabController,
  selection: ClosedFloatingPointRange<Float>,
  onSelectionChange: (ClosedFloatingPointRange<Float>) -> Unit,
  onRecord: (LabMode) -> Unit,
  onImport: () -> Unit,
  onTakes: () -> Unit,
  onSource: (Direction) -> Unit,
  onApply: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Text("Mock Call Lab", style = MaterialTheme.typography.titleLarge)
    if (view.retired) Text("Test ended. Real calls always take priority.", color = MaterialTheme.colorScheme.error)
    Text(view.message, style = MaterialTheme.typography.bodyMedium)

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      LabMode.entries.forEach { mode ->
        FilterChip(
          selected = view.mode == mode,
          enabled = !view.retired,
          onClick = { controller.setMode(mode) },
          label = { Text(when (mode) { LabMode.RECEIVED -> "Received"; LabMode.SENT -> "Sent"; LabMode.BOTH -> "Both" }) }
        )
      }
    }

    Text("Playback ${(view.stats.positionMs / 1000.0).toString().take(6)} s · selection ${view.selectionStartMs / 1000.0}–${view.selectionEndMs / 1000.0} s", style = MaterialTheme.typography.labelLarge)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Button(enabled = !view.retired && !view.recording, onClick = { onRecord(if (view.mode == LabMode.RECEIVED) LabMode.SENT else view.mode) }) { Text("Record microphone") }
      OutlinedButton(enabled = !view.retired, onClick = { if (view.playing) controller.pause() else controller.play() }) { Text(if (view.playing) "Stop / pause" else "Play") }
      OutlinedButton(enabled = !view.retired && !view.recording, onClick = { controller.play() }) { Text("Restart") }
      FilterChip(selected = view.loop, enabled = !view.retired, onClick = { controller.setLoop(!view.loop) }, label = { Text("Loop") })
      OutlinedButton(enabled = !view.retired, onClick = onImport) { Text("Import WAV") }
      OutlinedButton(enabled = !view.retired, onClick = onTakes) { Text("Takes / export") }
    }

    Direction.entries.forEach { direction ->
      val source = view.sources[direction.wireId]
      OutlinedButton(enabled = !view.retired, onClick = { onSource(direction) }, modifier = Modifier.fillMaxWidth()) {
        Text("${if (direction == Direction.RECEIVED) "Received source" else "Sent replay source"}: ${source?.label ?: "Choose a take"}")
      }
    }

    val duration = view.sources.mapNotNull { it?.durationMs }.minOrNull() ?: 0
    if (duration > 10) {
      Text("Loop / export selection", style = MaterialTheme.typography.titleSmall)
      RangeSlider(
        value = selection,
        onValueChange = onSelectionChange,
        valueRange = 0f..duration.toFloat(),
        onValueChangeFinished = {
          controller.selection(selection.start.toLong(), selection.endInclusive.toLong().coerceAtLeast(selection.start.toLong() + 10))
        }
      )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = view.original,
        enabled = !view.retired,
        onClick = { controller.original(!view.original) },
        label = { Text("Compare entire path: ${if (view.original) "Original" else "Processed"}") }
      )
      if (view.mode == LabMode.BOTH) {
        Monitor.entries.forEach { monitor ->
          FilterChip(
            selected = view.monitor == monitor,
            enabled = !view.retired,
            onClick = { controller.setMonitor(monitor) },
            label = { Text("Listen: ${monitor.name.lowercase()}") }
          )
        }
      }
    }

    Text("Settings A/B — same source and selection", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (i in 0..1) {
        TextButton(enabled = !view.retired, onClick = { controller.snapshot(i, true) }) { Text("Save ${if (i == 0) "A" else "B"}") }
        OutlinedButton(enabled = !view.retired, onClick = { controller.snapshot(i, false) }) { Text("Hear ${if (i == 0) "A" else "B"}") }
      }
      OutlinedButton(enabled = !view.retired && !view.recording, onClick = { controller.renderProcessed() }) { Text("Capture processed pass") }
    }

    val benchmark = view.benchmark
    Text("DeepFilter realtime benchmark", style = MaterialTheme.typography.titleMedium)
    Text(
      "Replays the same selected sample through Standard, Mobile Fused, and Low Latency using the real call worker. Safe dry fallback stays audible while timing and deadline misses are measured.",
      style = MaterialTheme.typography.bodySmall
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      OutlinedButton(
        enabled = !view.retired && !view.recording && !benchmark.running && (view.sources[Direction.RECEIVED.wireId]?.durationMs ?: 0) >= 3000,
        onClick = { controller.benchmark(Direction.RECEIVED) }
      ) { Text("Benchmark Received") }
      OutlinedButton(
        enabled = !view.retired && !view.recording && !benchmark.running && (view.sources[Direction.SENT.wireId]?.durationMs ?: 0) >= 3000,
        onClick = { controller.benchmark(Direction.SENT) }
      ) { Text("Benchmark Sent") }
      if (benchmark.running) {
        OutlinedButton(onClick = { controller.pause() }) { Text("Stop benchmark") }
      }
    }
    if (benchmark.running) {
      val current = when (benchmark.currentModel) {
        Model.STANDARD -> "Standard"
        Model.LOW_LATENCY -> "Low Latency"
        Model.MOBILE_FUSED -> "Mobile Fused"
        null -> "Preparing"
      }
      Text("Running ${benchmark.completed + 1}/${benchmark.total}: $current", style = MaterialTheme.typography.labelLarge)
    }
    benchmark.summary.results.forEach { result ->
      val modelLabel = when (result.model) {
        Model.STANDARD -> "Standard"
        Model.LOW_LATENCY -> "Low Latency"
        Model.MOBILE_FUSED -> "Mobile Fused"
      }
      Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(modelLabel, style = MaterialTheme.typography.titleSmall)
          Text(
            "mean ${String.format(Locale.ROOT, "%.2f", result.meanMs)} ms · p95 ${String.format(Locale.ROOT, "%.2f", result.p95Ms)} ms · RTF ${String.format(Locale.ROOT, "%.2f", result.rtfP95)}",
            style = MaterialTheme.typography.bodySmall
          )
          Text(
            "processed ${result.processed} · misses ${result.misses} · fallback ${if (result.fallbackOccurred) "yes" else "no"} · SNR ${String.format(Locale.ROOT, "%.1f", result.snr)} dB",
            style = MaterialTheme.typography.bodySmall
          )
          Text(
            if (result.realtimeSafe) "Realtime-safe" else "Did not meet realtime-safe criteria",
            color = if (result.realtimeSafe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
          )
        }
      }
    }
    if (!benchmark.running && benchmark.summary.realtimePick != null) {
      val pick = when (benchmark.summary.realtimePick) {
        Model.STANDARD -> "Standard"
        Model.LOW_LATENCY -> "Low Latency"
        Model.MOBILE_FUSED -> "Mobile Fused"
        null -> ""
      }
      Text("Realtime-safe speed pick: $pick", style = MaterialTheme.typography.labelLarge)
      Button(onClick = { controller.useBenchmarkPick() }) { Text("Use benchmark pick") }
    }

    Text("Microphone route comparison", style = MaterialTheme.typography.titleMedium)
    Text("Use the native call route button below for Normal call — Earpiece versus Speakerphone, then record separate takes and compare through one fixed output.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Fixed position", "Typical use").forEach { procedure ->
        FilterChip(selected = view.procedure == procedure, onClick = { controller.procedure(procedure) }, label = { Text(procedure) })
      }
    }
    Text(
      if (view.procedure == "Fixed position") "Keep phone, sound source, distance and volume unchanged; repeated speech is not sample-identical."
      else "Handset near your face versus speakerphone farther away includes posture and room differences.",
      style = MaterialTheme.typography.bodySmall
    )

    OutlinedButton(enabled = !view.retired, onClick = { onRecord(LabMode.BOTH) }) { Text("Speakerphone echo test") }
    Text("Select Speakerphone with the native route control and a Received clip first. The independent clip plays while the real call microphone records. Live microphone audio never feeds back into the speaker.", style = MaterialTheme.typography.bodySmall)

    Text("Recording taps", style = MaterialTheme.typography.titleSmall)
    for (tap in 0..3) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = view.tapMask and (1 shl tap) != 0,
          enabled = !view.playing && !view.retired,
          onCheckedChange = { checked ->
            val mask = if (checked) view.tapMask or (1 shl tap) else view.tapMask and (1 shl tap).inv()
            if (mask != 0) controller.setTapMask(mask)
          }
        )
        Text(MockCallLabController.tapName(tap), modifier = Modifier.weight(1f))
      }
    }

    Text("Recordings remain private until exported. 120 seconds per take; 256 MiB private storage limit. Saved takes remain until deleted.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !view.retired, onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Apply selected settings to calls") }
    Spacer(Modifier.height(24.dp))
  }
}
