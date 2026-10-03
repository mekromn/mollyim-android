#!/usr/bin/env python3
"""Compile controller/router glue against exact API-shaped fixtures, not an emulator."""
from pathlib import Path
import os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'app/src/main/java/org/thoughtcrime/securesms/webrtc/audio'
K=Path(shutil.which('kotlinc')).resolve().parent.parent
coroutines=K/'lib/kotlinx-coroutines-core-jvm.jar'
with tempfile.TemporaryDirectory() as td:
 w=Path(td)
 def write(name,text):
  p=w/name;p.write_text(text);return str(p)
 files=[]
 files.append(write('Android.kt','''package android
object Manifest {object permission {const val RECORD_AUDIO="record"}}
'''))
 files.append(write('Context.kt','''package android.content
import java.io.File
open class Context {val applicationContext:Context get()=this;val noBackupFilesDir=File(".");fun <T> getSystemService(c:Class<T>):T=c.getDeclaredConstructor().newInstance()}
'''))
 files.append(write('PackageManager.kt','''package android.content.pm
object PackageManager {const val PERMISSION_GRANTED=0}
'''))
 files.append(write('Os.kt','''package android.os
object Build {object VERSION {const val SDK_INT=36}}
object SystemClock {fun elapsedRealtime()=1L}
'''))
 files.append(write('Media.kt','''package android.media
class AudioDeviceInfo(val id:Int=1,val type:Int=1){companion object {const val TYPE_BUILTIN_EARPIECE=1;const val TYPE_BUILTIN_SPEAKER=2;const val TYPE_WIRED_HEADPHONES=3;const val TYPE_WIRED_HEADSET=4;const val TYPE_USB_HEADSET=5;const val TYPE_USB_DEVICE=6;const val TYPE_BLUETOOTH_SCO=7;const val TYPE_BLE_HEADSET=8}}
class AudioFormat(val sampleRate:Int=48000,val channelCount:Int=1)
class RecordingConfiguration(val audioDevice:AudioDeviceInfo?=null,val isClientSilenced:Boolean=false,val clientFormat:AudioFormat=AudioFormat())
class AudioManager {val availableCommunicationDevices=listOf<AudioDeviceInfo>();val communicationDevice:AudioDeviceInfo?=null;val activeRecordingConfigurations=listOf<RecordingConfiguration>();val isMicrophoneMute=false;val mode=3}
'''))
 files.append(write('Compat.kt','''package androidx.core.content
import android.content.Context
object ContextCompat {fun checkSelfPermission(c:Context,p:String)=0}
'''))
 files.append(write('Ring.kt','''package org.signal.ringrtc
class AudioConfig {var useOboe=false}
'''))
 files.append(write('Config.kt','''package org.thoughtcrime.securesms.service.webrtc
object RingRtcDynamicConfiguration {fun getAudioConfig()=org.signal.ringrtc.AudioConfig()}
'''))
 files.append(write('Controllers.kt','''package org.thoughtcrime.securesms.webrtc.audio
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
// The real controller delegates through a nullable coordinator. Preserve
// nullable Unit return types so the production adapter is actually checked.
object CallDenoiseController {val state=MutableStateFlow(DenoiseUiSnapshot());fun update(d:Direction,s:DenoiseSettings,p:Boolean):Unit?=null;fun setBypassed(d:Direction,b:Boolean):Unit?=null;fun retry(d:Direction):Unit?=null;fun reset(d:Direction):Unit?=null;fun readStatus(d:Direction)=DenoiseStatus();fun save(){}}
object IncomingAudioController {val settings=MutableStateFlow(IncomingAudioSettings());val available=true;fun initialize(c:Context){};fun update(s:IncomingAudioSettings,p:Boolean=true){};fun save(){}}
object IncomingAudioBridge {fun reset(){};fun readMeters(v:FloatArray)=0L}
object DeepFilterAssets {fun install(c:Context)=true}
sealed class AudioManagerCommand {class Initialize:AudioManagerCommand();class Start:AudioManagerCommand();class SetUserDevice(val recipient:Any?,val id:Int,val isId:Boolean):AudioManagerCommand()}
class SignalAudioManager {enum class AudioDevice {NONE};interface EventListener {fun onAudioDeviceChanged(a:AudioDevice,d:Set<AudioDevice>);fun onAudioDeviceChangeFailed();fun onBluetoothPermissionDenied()};fun configureForLocalTest(){};fun handleCommand(c:AudioManagerCommand){};fun shutdown(r:Runnable?){r?.run()};companion object {fun create(c:Context,l:EventListener?,t:Boolean)=SignalAudioManager()}}
'''))
 source=(A/'CallAudioSession.kt').read_text()
 files.append(write('CallAudioSession.kt',source))
 source=(A/'CallDenoiseController.kt').read_text();files.append(write('DenoiseStatus.kt','package org.thoughtcrime.securesms.webrtc.audio\n'+source[source.index('data class DenoiseStatus('):]))
 files += [str(A/(n+'.kt')) for n in ('CallDenoiseSettings','IncomingAudioSettings','DenoiseCoordinator')]
 files += [str(p) for p in (A/'mock').glob('*.kt') if p.name!='MockCallLabActivity.kt']
 result=subprocess.run(['kotlinc',*files,'-cp',str(coroutines),'-d',str(w/'glue.jar')],capture_output=True,text=True)
 if result.returncode:
  print(result.stdout+result.stderr);raise SystemExit(result.returncode)
 # A second compile/run includes the patched class fixture and exercises the
 # cached reflective control-plane bridge. The first compile above proves the
 # ordinary repository build does not require that class.
 patched=write('MockCallSession.kt','''package org.signal.ringrtc
import android.content.Context
class MockCallSession private constructor() {
  fun configure(v:FloatArray)=41L
  fun command(op:Int,a:Long,b:Long,c:Long,d:Long)=42L
  fun load(d:Int,r:Int,c:Int,p:ShortArray)=43L
  fun status(v:FloatArray)=true
  fun drain(t:Int,m:LongArray,p:FloatArray)=44
  fun revoke(){}
  fun close(){}
  companion object { @JvmStatic fun create(c:Context,a:AudioConfig)=MockCallSession() }
}
''')
 main=write('ReflectiveCheck.kt','''package org.thoughtcrime.securesms.webrtc.audio.mock
import android.content.Context
fun main() {
  val env=LabAndroidHardware.environment(Context(),LabToken(1,1))
  val hardware=env.acquire()
  check(hardware.native.configure(FloatArray(41))==41L)
  check(hardware.native.command(1,2,3,4,5)==42L)
  check(hardware.native.load(0,48000,1,ShortArray(480))==43L)
  check(hardware.native.status(FloatArray(128)))
  check(hardware.native.drain(0,LongArray(16),FloatArray(3840))==44)
  hardware.native.revoke()
  hardware.native.close()
  println("PASS cached reflective adapter resolves patched MockCallSession API")
}
''')
 runtimeJar=w/'runtime.jar'
 result=subprocess.run(['kotlinc',*files,patched,main,'-cp',str(coroutines),'-include-runtime','-d',str(runtimeJar)],capture_output=True,text=True)
 if result.returncode:
  print(result.stdout+result.stderr);raise SystemExit(result.returncode)
 result=subprocess.run(['java','-cp',str(runtimeJar)+':'+str(coroutines),'org.thoughtcrime.securesms.webrtc.audio.mock.ReflectiveCheckKt'],capture_output=True,text=True)
 if result.returncode:
  print(result.stdout+result.stderr);raise SystemExit(result.returncode)
 print(result.stdout.strip())
 print('PASS complete production/lab session adapters compile against stock RingRTC API without the lab-only MockCallSession class (API fixtures, NOT device execution).')
