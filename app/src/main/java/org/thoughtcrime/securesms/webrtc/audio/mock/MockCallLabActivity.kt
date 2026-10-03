/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import android.Manifest
import android.graphics.Color
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.webrtc.v2.MockCallScreen
import org.thoughtcrime.securesms.util.WindowUtil
import org.thoughtcrime.securesms.util.viewModel

class MockCallLabViewModel(val controller:MockCallLabController?):ViewModel(){
  var exportTake:String?=null
  var exportTrack:Int=0
  var reportJson=false
  var pendingRecordMode=LabMode.SENT
  override fun onCleared(){controller?.close()}
}

/** Internal passphrase-protected screen. No exported intent starts recording. */
class MockCallLabActivity:PassphraseRequiredActivity(){
  private val model by viewModel { MockCallLabViewModel(MockCallLabRuntime.open(applicationContext)) }
  private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
    if(granted)recordExplicitly() else toast("Microphone permission was not granted")
  }
  private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
    if(uri!=null)model.controller?.importWave({requireNotNull(contentResolver.openInputStream(uri)){"Selected WAV could not be opened"}},"Imported WAV")
  }
  private val exportWave=registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")){uri->
    val id=model.exportTake
    if(uri!=null&&id!=null)model.controller?.exportTrack(id,model.exportTrack){requireNotNull(contentResolver.openOutputStream(uri,"w")){"Export destination unavailable"}}
    model.exportTake=null
  }
  private val exportReport=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
    if(uri!=null)model.controller?.exportReport(model.reportJson){requireNotNull(contentResolver.openOutputStream(uri,"w")){"Export destination unavailable"}}
  }
  override fun onCreate(savedInstanceState:Bundle?,ready:Boolean){
    super.onCreate(savedInstanceState,ready)
    WindowUtil.clearTranslucentNavigationBar(window)
    WindowUtil.clearTranslucentStatusBar(window)
    enableEdgeToEdge(
      statusBarStyle=SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle=SystemBarStyle.dark(Color.TRANSPARENT)
    )
    if(Build.VERSION.SDK_INT>=29){
      window.isNavigationBarContrastEnforced = false
      window.isStatusBarContrastEnforced = false
    }
    volumeControlStream=AudioManager.STREAM_VOICE_CALL
    val controller=model.controller
    if(controller==null){toast("A real call or another local test owns the audio resources");finish();return}
    onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){requestExit()}})
    setContent { SignalTheme {
      MockCallScreen(
        controller=controller,
        onExit=::requestExit,
        onRecord={mode->
          model.pendingRecordMode=mode
          if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)recordExplicitly()
          else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onImport={controller.pause();importer.launch(arrayOf("audio/wav","audio/x-wav","audio/wave","application/octet-stream"))},
        onExportTrack={take,index->controller.pause();model.exportTake=take.id;model.exportTrack=index;exportWave.launch("Molly-${if(take.tracks[index].tap in listOf(2,3,4))"processed" else "source"}.wav")},
        onExportReport={json->model.reportJson=json;exportReport.launch(if(json)"Molly-call-lab-diagnostics.json" else "Molly-call-lab-diagnostics.txt")}
      )
    }}
  }
  private fun recordExplicitly(){
    val c=model.controller?:return
    if(c.view.value.retired)return
    c.setMode(model.pendingRecordMode)
    c.record()
  }
  override fun onStart(){super.onStart();model.controller?.setVisible(true)}
  override fun onStop(){
    if(!isChangingConfigurations)model.controller?.setVisible(false)
    super.onStop()
  }
  private fun requestExit(){
    val c=model.controller?:run{finish();return}
    if(c.view.value.retired){c.close();finish();return}
    val unsaved=c.view.value.recording||c.view.value.takes.any{!it.saved}
    c.pause()
    if(!unsaved){c.close();finish();return}
    MaterialAlertDialogBuilder(this)
      .setTitle("End local test?")
      .setMessage("Unsaved recordings will be discarded. Saved takes and your ordinary call settings remain unchanged.")
      .setPositiveButton("End test"){_,_->c.close();finish()}
      .setNegativeButton("Keep lab open",null).show()
  }
  private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
}
