/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.webrtc.audio.Direction
import org.thoughtcrime.securesms.webrtc.audio.mock.*

/** Local display identity, not a Recipient or a fabricated server call. Shared
 * native session + Call audio panel + ordinary call button components are reused. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun MockCallScreen(
  controller:MockCallLabController,onExit:()->Unit,onRecord:(LabMode)->Unit,onImport:()->Unit,
  onExportTrack:(LabTake,Int)->Unit,onExportReport:(Boolean)->Unit
){
  val view by controller.view.collectAsState()
  var diagnostics by rememberSaveable{mutableStateOf(false)}
  var sourcesFor by remember{mutableStateOf<Direction?>(null)}
  var routes by rememberSaveable{mutableStateOf(false)}
  var takes by rememberSaveable{mutableStateOf(false)}
  var apply by rememberSaveable{mutableStateOf(false)}
  var chosenSections by remember{mutableStateOf(LabSection.entries.toSet())}
  var processedConfirmation by remember{mutableStateOf<Triple<Direction,LabTake,Int>?>(null)}
  var selection by remember(view.selectionStartMs,view.selectionEndMs){mutableStateOf(view.selectionStartMs.toFloat()..view.selectionEndMs.coerceAtLeast(view.selectionStartMs+10).toFloat())}
  Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
          Text("Mock Call Lab",style=MaterialTheme.typography.titleLarge)
          Text("LOCAL TEST — Nobody is connected",style=MaterialTheme.typography.labelMedium,modifier=Modifier.testTag("lab-local-only"))
        }
        TextButton(onClick=onExit){Text("Close")}
      }
      LabStatsPanel(view,compact=true,onExpand={diagnostics=true})
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
        if(view.retired)Text("Test ended. Real calls always take priority.",color=MaterialTheme.colorScheme.error)
        Text(view.message,style=MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
          LabMode.entries.forEach{mode->FilterChip(selected=view.mode==mode,enabled=!view.retired,onClick={controller.setMode(mode)},label={Text(when(mode){LabMode.RECEIVED->"Received";LabMode.SENT->"Sent";LabMode.BOTH->"Both"})})}
        }
        Text("Playback ${(view.stats.positionMs/1000.0).toString().take(6)} s · selection ${view.selectionStartMs/1000.0}–${view.selectionEndMs/1000.0} s",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
          Button(enabled=!view.retired&&!view.recording,onClick={onRecord(if(view.mode==LabMode.RECEIVED)LabMode.SENT else view.mode)}){Text("Record microphone")}
          OutlinedButton(enabled=!view.retired,onClick={if(view.playing)controller.pause()else controller.play()}){Text(if(view.playing)"Stop / pause" else "Play")}
          OutlinedButton(enabled=!view.retired,onClick={controller.play()}){Text("Restart")}
          FilterChip(selected=view.loop,enabled=!view.retired,onClick={controller.setLoop(!view.loop)},label={Text("Loop")})
          OutlinedButton(enabled=!view.retired,onClick=onImport){Text("Import WAV")}
          OutlinedButton(enabled=!view.retired,onClick={takes=true}){Text("Takes / export")}
        }
        if(view.recording)Text("● RECORDING — physical call microphone · ${view.actualRoute}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("lab-recording"))
        Direction.entries.forEach{direction->
          val source=view.sources[direction.wireId]
          OutlinedButton(enabled=!view.retired,onClick={sourcesFor=direction},modifier=Modifier.fillMaxWidth()){
            Text("${if(direction==Direction.RECEIVED)"Received source" else "Sent replay source"}: ${source?.label?:"Choose a take"}")
          }
        }
        val duration=view.sources.mapNotNull{it?.durationMs}.minOrNull()?:0
        if(duration>10){
          Text("Loop / export selection",style=MaterialTheme.typography.titleSmall)
          RangeSlider(value=selection,onValueChange={selection=it},valueRange=0f..duration.toFloat(),
            onValueChangeFinished={controller.selection(selection.start.toLong(),selection.endInclusive.toLong().coerceAtLeast(selection.start.toLong()+10))})
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
          FilterChip(selected=view.original,enabled=!view.retired,onClick={controller.original(!view.original)},label={Text("Compare entire path: ${if(view.original)"Original" else "Processed"}")})
          if(view.mode==LabMode.BOTH)Monitor.entries.forEach{m->FilterChip(selected=view.monitor==m,enabled=!view.retired,onClick={controller.setMonitor(m)},label={Text("Listen: ${m.name.lowercase()}")})}
        }
        Text("Settings A/B — same source and selection",style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
          for(i in 0..1){
            TextButton(enabled=!view.retired,onClick={controller.snapshot(i,true)}){Text("Save ${if(i==0)"A" else "B"}")}
            OutlinedButton(enabled=!view.retired,onClick={controller.snapshot(i,false)}){Text("Hear ${if(i==0)"A" else "B"}")}
          }
          OutlinedButton(enabled=!view.retired&&!view.recording,onClick={controller.renderProcessed()}){Text("Capture processed pass")}
        }
        Text("Microphone route comparison",style=MaterialTheme.typography.titleMedium)
        Text("Record one take in Normal call — Earpiece and another in Speakerphone. Compare both through one fixed output. Added DeepFilterNet is separate from the phone’s capture processing.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
          listOf("Fixed position","Typical use").forEach{p->FilterChip(selected=view.procedure==p,onClick={controller.procedure(p)},label={Text(p)})}
        }
        Text(if(view.procedure=="Fixed position")"Keep phone, sound source, distance and volume unchanged; repeated speech is not sample-identical." else "Handset near your face versus speakerphone farther away includes posture and room differences.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!view.retired,onClick={onRecord(LabMode.BOTH)}){Text("Speakerphone echo test")}
        Text("For the echo test, select Speakerphone and a Received clip first. The clip plays through the speaker while the microphone records. Live microphone audio never feeds back into the speaker.",style=MaterialTheme.typography.bodySmall)
        Text("Recording taps",style=MaterialTheme.typography.titleSmall)
        for(tap in 0..3){
          Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=view.tapMask and (1 shl tap)!=0,enabled=!view.playing&&!view.retired,onCheckedChange={checked->
              val mask=if(checked)view.tapMask or (1 shl tap) else view.tapMask and (1 shl tap).inv()
              if(mask!=0)controller.setTapMask(mask)
            })
            Text(MockCallLabController.tapName(tap),modifier=Modifier.weight(1f))
          }
        }
        Text("Recordings remain private until exported. 120 seconds per take; 256 MiB private storage limit. Saved takes remain until deleted.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!view.retired,onClick={apply=true},modifier=Modifier.fillMaxWidth()){Text("Apply selected settings to calls")}
        Spacer(Modifier.height(8.dp))
      }
      CallAudioControls(onSheetDisplayChanged={},session = controller,statsHeader={LabStatsPanel(view,compact=true,onExpand={diagnostics=true})})
      Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
        OutlinedButton(enabled=!view.retired,onClick={routes=true;controller.prepareRoutes()}){Text("Audio route")}
        ToggleMicButton(isMicEnabled=!view.stats.muted,onChange={controller.setMuted(!it)})
        HangupButton(onClick=onExit)
      }
    }
  }
  if(diagnostics)LabDiagnosticsSheet(controller,view,{diagnostics=false},onExportReport)
  if(routes)AlertDialog(onDismissRequest={routes=false},title={Text("Communication audio route")},text={
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
      if(view.routes.isEmpty())Text("Preparing route list. No microphone is opened by this action.")
      view.routes.forEach{r->OutlinedButton(onClick={controller.selectRoute(r.id);routes=false},modifier=Modifier.fillMaxWidth()){Text(r.label)}}
      Text("Actual: ${view.actualRoute}\n${view.inputRoute}",style=MaterialTheme.typography.bodySmall)
    }
  },confirmButton={TextButton(onClick={routes=false}){Text("Close")}})
  sourcesFor?.let{direction->AlertDialog(onDismissRequest={sourcesFor=null},title={Text("Choose ${direction.name.lowercase()} sample")},text={
    Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())){
      view.takes.forEach{take->
        Text(take.label,style=MaterialTheme.typography.titleSmall)
        take.tracks.forEachIndexed{i,track->TextButton(onClick={
          sourcesFor=null
          if(track.tap in listOf(2,3,4))processedConfirmation=Triple(direction,take,i)
          else controller.selectSource(direction,take.id,i)
        }){Text("${MockCallLabController.tapName(track.tap)} · ${track.rate} Hz / ${track.channels} ch${if(!take.complete)" · INCOMPLETE" else ""}")}}
      }
      if(view.takes.isEmpty())Text("Record a microphone sample or import a WAV first.")
    }
  },confirmButton={TextButton(onClick={sourcesFor=null}){Text("Close")}})}
  processedConfirmation?.let{pending->AlertDialog(onDismissRequest={processedConfirmation=null},title={Text("Use already-processed audio?")},text={Text("This track already includes processing. Applying effects again will process it a second time; the original remains unchanged.")},confirmButton={TextButton(onClick={controller.selectSource(pending.first,pending.second.id,pending.third,true);processedConfirmation=null}){Text("Use this source")}},dismissButton={TextButton(onClick={processedConfirmation=null}){Text("Cancel")}})}
  if(takes)AlertDialog(onDismissRequest={takes=false},title={Text("Local takes")},text={
    Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
      view.takes.forEach{take->
        Text("${take.label} — ${if(take.complete)"Complete" else "Incomplete: ${take.reason}"}",style=MaterialTheme.typography.titleSmall)
        Row{TextButton(onClick={controller.saveTake(take.id)},enabled=!take.saved){Text(if(take.saved)"Saved" else "Save")};TextButton(onClick={controller.deleteTake(take.id)}){Text("Delete")}}
        take.tracks.forEachIndexed{i,t->TextButton(onClick={onExportTrack(take,i)}){Text("Export ${MockCallLabController.tapName(t.tap)} (${t.encoding.name})")}}
      }
      if(view.takes.isEmpty())Text("No completed or incomplete takes yet.")
    }
  },confirmButton={TextButton(onClick={takes=false}){Text("Close")}})
  if(apply)AlertDialog(onDismissRequest={apply=false},title={Text("Apply to ordinary calls?")},text={Column{
    Text("Only the selected settings will be saved. Test playback, recordings and monitoring are not applied.")
    LabSection.entries.forEach{s->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(s in chosenSections,onCheckedChange={checked->chosenSections=if(checked)chosenSections+s else chosenSections-s});Text(when(s){LabSection.RECEIVED_DENOISE->"Received DeepFilterNet";LabSection.SENT_DENOISE->"Sent DeepFilterNet";LabSection.RECEIVED_EFFECTS->"Received EQ, compressor, gain and limiter"})}}
  }},confirmButton={TextButton(enabled=chosenSections.isNotEmpty(),onClick={controller.applyToCalls(chosenSections);apply=false}){Text("Apply")}},dismissButton={TextButton(onClick={apply=false}){Text("Cancel")}})
}
