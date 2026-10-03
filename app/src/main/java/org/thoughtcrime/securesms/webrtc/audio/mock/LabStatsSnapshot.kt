/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.util.Locale
import kotlin.math.log10

data class LabDirectionStats(
  val state:Int=0,val rate:Int=0,val channels:Int=0,val configuredDelayMs:Float=0f,
  val misses:Long=0,val inputPeak:Float=0f,val outputPeak:Float=0f,val snr:Float=0f,
  val meanMs:Float=0f,val p95Ms:Float=0f,val levelsValid:Boolean=false,
  val inferenceValid:Boolean=false,val processed:Long=0,val bypassed:Boolean=false
) {
  val label:String get()=listOf("Off","Loading / warming up","Active","Compare original","Unavailable","Bypassed: overloaded","Muted","Unsupported format","Zero suppression").getOrElse(state){"Unavailable"}
}
data class LabTapStats(
  val valid:Boolean=false,val rate:Int=0,val channels:Int=0,val blocks:Long=0,
  val peak:Float=0f,val rms:Float=0f,val overs:Long=0,val saturation:Long=0,val samples:Long=0
)
data class LabStatsSnapshot(
  val endpointState:Int=0,val error:Int=0,val command:Long=0,val hardwareStopped:Boolean=true,
  val epoch:Long=0,val segment:Long=0,val mode:Int=0,val positionMs:Long=0,val finished:Boolean=false,
  val warming:Boolean=false,val dropped:Long=0,val incomplete:Boolean=false,val muted:Boolean=false,
  val received:LabDirectionStats=LabDirectionStats(),val sent:LabDirectionStats=LabDirectionStats(),
  val taps:List<LabTapStats> = List(5){LabTapStats()},val effectsMeters:List<Float> = List(6){0f},
  val effectCallbacks:Long=0,val configuration:Long=0,val inputDeviceDelayMs:Float?=null,
  val outputDeviceDelayMs:Float?=null,val collectedAtMs:Long=0
) {
  companion object {
    fun parse(v:FloatArray,nowMs:Long):LabStatsSnapshot? {
      if(v.size!=128||v.any{!it.isFinite()}||v[0]!=1f||v[1].toInt() !in 0..5||v[7].toInt() !in 0..2)return null
      fun direction(o:Int):LabDirectionStats? {
        if(v[o].toInt() !in 0..8||v[o+1]<0||v[o+2]<0||v[o+3]<0||v[o+3]>1000||v[o+4]<0||v[o+8]<0||v[o+9]<0||v[o+12]<0)return null
        return LabDirectionStats(v[o].toInt(),v[o+1].toInt(),v[o+2].toInt(),v[o+3],v[o+4].toLong(),v[o+5],v[o+6],v[o+7],v[o+8]/1000,v[o+9]/1000,v[o+10]!=0f,v[o+11]!=0f,v[o+12].toLong(),v[o+14]!=0f)
      }
      val received=direction(16)?:return null;val sent=direction(32)?:return null
      val taps=List(5){i->val o=48+12*i;LabTapStats(v[o]!=0f,v[o+1].toInt(),v[o+2].toInt(),v[o+3].toLong(),v[o+4],v[o+5],v[o+6].toLong(),v[o+7].toLong(),v[o+8].toLong())}
      if(taps.any{it.blocks<0||it.peak<0||it.rms<0||it.overs<0||it.saturation<0||it.samples<0})return null
      return LabStatsSnapshot(v[1].toInt(),v[2].toInt(),v[3].toLong(),v[4]!=0f,v[5].toLong(),v[6].toLong(),v[7].toInt(),v[8].toLong(),v[9]!=0f,v[10]!=0f,v[11].toLong(),v[12]!=0f,v[13]!=0f,received,sent,taps,v.slice(108..113),v[114].toLong(),v[115].toLong(),v[116].takeIf{it>=0},v[117].takeIf{it>=0},nowMs)
    }
  }
}

/** Freezing readouts never hides revocation, mute, errors or recording failures. */
class LabStatsRepository {
  private var latest=LabStatsSnapshot()
  private var held:LabStatsSnapshot?=null
  private var baseline=LabStatsSnapshot()
  @Synchronized fun publish(value:LabStatsSnapshot){
    if(value.collectedAtMs<latest.collectedAtMs)return
    if(value.epoch!=latest.epoch||value.segment!=latest.segment||value.configuration!=latest.configuration)baseline=value
    latest=value
  }
  @Synchronized fun reset(){baseline=latest}
  @Synchronized fun windowDropped():Long=(latest.dropped-baseline.dropped).coerceAtLeast(0)
  @Synchronized fun freeze(value:Boolean){held=if(value)latest else null}
  @Synchronized fun display():LabStatsSnapshot=(held?:latest).copy(endpointState=latest.endpointState,error=latest.error,muted=latest.muted,incomplete=latest.incomplete,dropped=latest.dropped,hardwareStopped=latest.hardwareStopped)
}

object LabReport {
  fun dbfs(value:Float):Float?=if(value>0&&value.isFinite())20f*log10(value) else null
  private fun number(value:Float?):String=if(value==null||!value.isFinite())"Unavailable" else String.format(Locale.ROOT,"%.3f",value)
  private fun escaped(value:String):String=buildString {
    append('"');for(c in value.take(512))when(c){'"'->append("\\\"");'\\'->append("\\\\");'\n'->append("\\n");'\r'->append("\\r");'\t'->append("\\t");else->if(c<' ')append(String.format(Locale.ROOT,"\\u%04x",c.code))else append(c)};append('"')
  }
  private fun jsonNumber(v:Float?):String=if(v==null||!v.isFinite())"null" else v.toString()
  fun text(s:LabStatsSnapshot,route:String,backend:String):String=buildString {
    append("Molly Audio Mock Call Lab — local diagnostics\nSchema: 1\n")
    append("Actual route: ${route.take(160)}\nBackend: ${backend.take(160)}\n")
    append("Network latency / loss: Not applicable — no remote call\n")
    for((name,d) in listOf("Received" to s.received,"Sent" to s.sent)){
      append("\n$name: ${d.label}\nConfigured added audio delay: ${number(d.configuredDelayMs)} ms\n")
      append("Measured inference mean / p95: ${number(d.meanMs.takeIf{d.inferenceValid})} / ${number(d.p95Ms.takeIf{d.inferenceValid})} ms per block\n")
      append("Inference sample count: ${d.processed}; rolling missed blocks: ${d.misses}\n")
      append("Format: ${d.rate} Hz, ${d.channels} channel(s); block: 10 ms\n")
      append("Input / output digital peak: ${number(dbfs(d.inputPeak).takeIf{d.levelsValid})} / ${number(dbfs(d.outputPeak).takeIf{d.levelsValid})} dBFS\n")
      append("Model-estimated SNR: ${number(d.snr.takeIf{d.inferenceValid})} dB\n")
    }
    append("\nEstimated input / output device delay: ${number(s.inputDeviceDelayMs)} / ${number(s.outputDeviceDelayMs)} ms\n")
    append("Local acoustic round trip: Unavailable unless a reference test succeeds\n")
    append("Recording dropped blocks: ${s.dropped}; incomplete: ${s.incomplete}; muted: ${s.muted}\n")
    val names=listOf("Call-backend microphone input","Before sent DeepFilterNet","After sent DeepFilterNet","Playback reference","Received result")
    s.taps.forEachIndexed{i,t->if(t.valid)append("${names[i]}: ${t.rate} Hz / ${t.channels} ch, peak ${number(dbfs(t.peak))} dBFS, RMS ${number(dbfs(t.rms))} dBFS, peak overs ${t.overs}, PCM saturation samples ${t.saturation}, blocks ${t.blocks}\n")}
    append("Device and network delay are not included in configured DSP delay. Received and Sent run in parallel. Digital levels are not acoustic SPL.\n")
  }
  fun json(s:LabStatsSnapshot,route:String,backend:String):String {
    fun direction(d:LabDirectionStats)="""{"state":${escaped(d.label)},"configured_delay_ms":${jsonNumber(d.configuredDelayMs)},"inference_mean_ms":${jsonNumber(d.meanMs.takeIf{d.inferenceValid})},"inference_p95_ms":${jsonNumber(d.p95Ms.takeIf{d.inferenceValid})},"processed":${d.processed},"missed_rolling":${d.misses},"rate":${d.rate},"channels":${d.channels},"peak_before_dbfs":${jsonNumber(dbfs(d.inputPeak).takeIf{d.levelsValid})},"peak_after_dbfs":${jsonNumber(dbfs(d.outputPeak).takeIf{d.levelsValid})}}"""
    return """{"schema":1,"actual_route":${escaped(route)},"backend":${escaped(backend)},"network":"not_applicable","received":${direction(s.received)},"sent":${direction(s.sent)},"input_device_ms":${jsonNumber(s.inputDeviceDelayMs)},"output_device_ms":${jsonNumber(s.outputDeviceDelayMs)},"dropped_recording_blocks":${s.dropped},"incomplete":${s.incomplete}}"""
  }
}
