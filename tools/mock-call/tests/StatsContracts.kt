/* SPDX-License-Identifier: AGPL-3.0-only */
import org.thoughtcrime.securesms.webrtc.audio.mock.*
fun main(){
 fun packet()=FloatArray(128).also{it[0]=1f;it[1]=2f;it[5]=2f;it[6]=1f;it[16]=2f;it[17]=48000f;it[18]=1f;it[19]=50f;it[24]=1700f;it[25]=2200f;it[26]=1f;it[27]=1f;it[28]=100f;it[116]=-1f;it[117]=-1f}
 val parsed=LabStatsSnapshot.parse(packet(),1000)!!
 check(parsed.received.meanMs==1.7f && parsed.received.p95Ms==2.2f && parsed.received.configuredDelayMs==50f)
 check(parsed.inputDeviceDelayMs==null && parsed.outputDeviceDelayMs==null)
 check(LabStatsSnapshot.parse(FloatArray(2),1000)==null)
 check(LabStatsSnapshot.parse(packet().also{it[16]=Float.NaN},1000)==null)
 val store=LabStatsRepository()
 store.publish(parsed)
 store.freeze(true)
 store.publish(parsed.copy(endpointState=4,muted=true,received=parsed.received.copy(configuredDelayMs=99f),collectedAtMs=1100))
 check(store.display().endpointState==4 && store.display().muted && store.display().received.configuredDelayMs==50f)
 store.freeze(false);check(store.display().received.configuredDelayMs==99f)
 val text=LabReport.text(parsed,"Speakerphone","Java ADM")
 check("Configured" in text && "Measured" in text && "Not applicable" in text && "Unavailable" in text)
 check(!text.contains("account",true))
 val json=LabReport.json(parsed,"Speaker\"phone","Java ADM")
 check(json.contains("Speaker\\\"phone") && json.contains("\"input_device_ms\":null"))
 check(LabReport.dbfs(0f)==null && LabReport.dbfs(1f)==0f)
 val off=parsed.copy(received=parsed.received.copy(state=0,levelsValid=false,inferenceValid=false,configuredDelayMs=0f))
 check("0.0" in LabReport.text(off,"Handset","Java"))
 println("PASS: stats ABI, units, unavailable delay, frozen critical status and safe report formatting")
}
