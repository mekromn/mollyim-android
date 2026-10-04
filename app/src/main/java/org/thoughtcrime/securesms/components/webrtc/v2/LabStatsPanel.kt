/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.components.webrtc.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.webrtc.audio.mock.*
import java.util.Locale

private fun ms(value:Float)=String.format(Locale.ROOT,"%.1f",value)
@Composable
fun LabStatsPanel(view:MockCallLabController.View,compact:Boolean=false,onExpand:()->Unit={}){
  if (compact) {
    val rx=view.stats.received
    val tx=view.stats.sent
    val rxP95=if(view.playing&&rx.inferenceValid)ms(rx.p95Ms) else "—"
    val txP95=if(view.playing&&tx.inferenceValid)ms(tx.p95Ms) else "—"
    val misses=if(view.playing)rx.misses+tx.misses else 0L
    Surface(
      shape=RoundedCornerShape(12.dp),
      border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
      modifier=Modifier.fillMaxWidth().testTag("lab-live-stats").clickable(onClick=onExpand)
    ){
      Column(Modifier.padding(horizontal=12.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
        Text("${view.actualRoute} · ${view.phase}",style=MaterialTheme.typography.labelMedium)
        Text("Rx p95 $rxP95 ms · Tx p95 $txP95 ms · misses $misses",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(view.stats.dropped>0||view.stats.incomplete)Text("Recording warning: ${view.stats.dropped} dropped blocks",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
      }
    }
    return
  }
  Surface(shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("lab-live-stats").clickable(onClick=onExpand)){
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      Text("Live audio stats${if(view.frozen)" — DISPLAY PAUSED" else ""}",style=MaterialTheme.typography.labelLarge)
      Text("${view.actualRoute} · ${view.phase}",style=MaterialTheme.typography.labelMedium)
      for((label,d) in listOf("Received" to view.stats.received,"Sent" to view.stats.sent)){
        Text("$label: ${if(view.playing)d.label else "Inactive"} · delay ${if(view.playing)ms(d.configuredDelayMs) else "—"} ms configured",style=MaterialTheme.typography.labelMedium)
        Text("Block time ${if(d.inferenceValid&&view.playing)ms(d.meanMs)+" / "+ms(d.p95Ms) else "— / —"} ms mean / p95 · missed ${if(view.playing)d.misses.toString() else "—"}",style=MaterialTheme.typography.bodySmall)
        if(d.levelsValid&&view.playing){
          Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            LinearProgressIndicator(progress={d.inputPeak.coerceIn(0f,1f)},modifier=Modifier.weight(1f).height(4.dp))
            LinearProgressIndicator(progress={d.outputPeak.coerceIn(0f,1f)},modifier=Modifier.weight(1f).height(4.dp))
          }
        }
      }
      if(view.stats.dropped>0||view.stats.incomplete)Text("Recording warning: ${view.stats.dropped} dropped blocks; incomplete=${view.stats.incomplete}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
    }
  }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabDiagnosticsSheet(controller:MockCallLabController,view:MockCallLabController.View,onDismiss:()->Unit,onExport:(Boolean)->Unit){
  ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal=20.dp)){
      Text("Call-path diagnostics",style=MaterialTheme.typography.headlineSmall)
      Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        TextButton(onClick={controller.freezeStats(!view.frozen)}){Text(if(view.frozen)"Resume display" else "Pause display")}
        TextButton(onClick={controller.resetStats()}){Text("Reset stats")}
        TextButton(onClick=onDismiss){Text("Close")}
      }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(LabReport.text(view.stats,view.actualRoute,view.backend),style=MaterialTheme.typography.bodyMedium)
        Text(view.inputRoute,style=MaterialTheme.typography.bodyMedium)
        Text("Device delay estimates and vendor microphone internals may be unavailable. No network call is connected. Recording taps preserve the call backend’s actual processing; they are not raw ADC recordings.",style=MaterialTheme.typography.bodySmall)
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(onClick={onExport(false)}){Text("Export text")}
        OutlinedButton(onClick={onExport(true)}){Text("Export JSON")}
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}
