/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import android.content.Context
import android.os.SystemClock
import org.signal.ringrtc.MockCallSession
import org.thoughtcrime.securesms.service.webrtc.RingRtcDynamicConfiguration
import org.thoughtcrime.securesms.webrtc.audio.DeepFilterAssets
import java.io.File
import java.util.concurrent.TimeUnit

/** Production adapter: the factory and ADM are the calling stack's existing
 * implementation. A LabNativePort is not a standalone AudioRecord/MediaPlayer. */
internal object LabAndroidHardware {
  fun environment(context:Context,token:LabToken)=LabControllerEnvironment(
    directory=File(context.noBackupFilesDir,"mock-call"),
    acquire={
      val configuration=RingRtcDynamicConfiguration.getAudioConfig()
      val route=LabAndroidRoute(context)
      val session=try{MockCallSession.create(context,configuration)}catch(e:Exception){
        route.shutdown().get(2,TimeUnit.SECONDS);throw e
      }
      val native=object:LabNativePort {
        override fun configure(values:FloatArray)=session.configure(values)
        override fun command(op:Int,a:Long,b:Long,c:Long,d:Long)=session.command(op,a,b,c,d)
        override fun load(direction:Int,rate:Int,channels:Int,pcm:ShortArray)=session.load(direction,rate,channels,pcm)
        override fun status(values:FloatArray)=session.status(values)
        override fun drain(tap:Int,metadata:LongArray,pcm:FloatArray)=session.drain(tap,metadata,pcm)
        override fun revoke()=session.revoke()
        override fun close()=session.close()
      }
      LabHardwareObjects(native,route,if(configuration.useOboe)"Oboe — production configuration" else "Java ADM — production configuration")
    },
    installModels={DeepFilterAssets.install(context)},
    isCurrent={MockCallLabRuntime.valid(token)},
    apply={value,sections->MockCallLabRuntime.coordinator.applyToCalls(token,value,sections)},
    onClose={MockCallLabRuntime.close(token)},
    retirement=MockCallLabRuntime.retirement,
    disposal=MockCallLabRuntime.disposal,
    nowMs={SystemClock.elapsedRealtime()}
  )
}
