/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCommand
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses the app communication router; constructing it does not start capture.
 * Selected-route events are requests, not proof: observations query the actual
 * communication device and, when uniquely observable, the active input config.
 */
internal class LabAndroidRoute(private val context:Context) : LabRoutePort {
  private val manager=context.getSystemService(AudioManager::class.java)
  private val stopping=AtomicBoolean(false)
  private val done=CompletableFuture<Void>()
  @Volatile override var routeError=false;private set
  private val router=SignalAudioManager.create(context,object:SignalAudioManager.EventListener {
    override fun onAudioDeviceChanged(activeDevice:SignalAudioManager.AudioDevice,devices:Set<SignalAudioManager.AudioDevice>) {}
    override fun onAudioDeviceChangeFailed(){routeError=true}
    override fun onBluetoothPermissionDenied(){routeError=true}
  },false).also{it.configureForLocalTest()}
  private var initialized=false

  override fun choices():List<LabRouteChoice> {
    if(Build.VERSION.SDK_INT<31)return emptyList()
    return manager.availableCommunicationDevices.mapNotNull { device ->
      when(device.type){
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> LabRouteChoice(device.id,CallRoute.HANDSET,"Normal call — Earpiece")
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LabRouteChoice(device.id,CallRoute.SPEAKERPHONE,"Speakerphone")
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET -> LabRouteChoice(device.id,CallRoute.HEADSET,"Wired headset")
        AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE -> LabRouteChoice(device.id,CallRoute.HEADSET,"USB audio")
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET -> LabRouteChoice(device.id,CallRoute.HEADSET,"Bluetooth call audio")
        else -> null
      }
    }
  }
  override fun start(choice:LabRouteChoice) {
    check(!stopping.get())
    check(Build.VERSION.SDK_INT>=31) { "Confirmed local route testing requires Android 12 or later" }
    check(choices().any{it.id==choice.id&&it.route==choice.route}) { "Requested communication route is unavailable" }
    routeError=false
    if(!initialized){
      initialized=true
      router.handleCommand(AudioManagerCommand.Initialize())
      router.handleCommand(AudioManagerCommand.Start())
    }
    router.handleCommand(AudioManagerCommand.SetUserDevice(null,choice.id,true))
  }
  override fun observe():LabRouteObservation {
    val out=if(Build.VERSION.SDK_INT>=31)manager.communicationDevice else null
    val selected=choices().firstOrNull{it.id==out?.id}
    val configs=runCatching{manager.activeRecordingConfigurations}.getOrDefault(emptyList())
    // Do not attribute another concurrently running recorder to this endpoint.
    val config=configs.singleOrNull()
    val input=config?.audioDevice
    val silenced=manager.isMicrophoneMute||(Build.VERSION.SDK_INT>=29&&config?.isClientSilenced==true)
    return LabRouteObservation(selected,input?.id,if(input==null||config==null)"Input device details unavailable" else "Platform input type ${input.type}; ${config.clientFormat.sampleRate} Hz / ${config.clientFormat.channelCount} ch (backend identity not confirmed)",silenced,manager.mode)
  }
  override fun microphonePermission():Boolean = ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
  override fun shutdown():CompletableFuture<Void> {
    if(stopping.compareAndSet(false,true))router.shutdown(Runnable{done.complete(null)})
    return done
  }
}
