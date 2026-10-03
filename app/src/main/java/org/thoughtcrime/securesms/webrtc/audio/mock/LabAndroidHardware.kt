/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import android.content.Context
import android.os.SystemClock
import org.thoughtcrime.securesms.service.webrtc.RingRtcDynamicConfiguration
import org.thoughtcrime.securesms.webrtc.audio.DeepFilterAssets
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Cached control-plane bridge to the lab-only RingRTC class. Normal repository
 * builds intentionally use stock RingRTC, so the app must not require this
 * extension at compile time. The signed lab build injects the patched AAR.
 *
 * Reflection is never used on an audio callback: commands/status run on the
 * lab controller executor while capture/render/DSP stay inside native RingRTC.
 */
private class PatchedMockCallSession private constructor(
  private val target: Any,
  private val configureMethod: Method,
  private val commandMethod: Method,
  private val loadMethod: Method,
  private val statusMethod: Method,
  private val drainMethod: Method,
  private val revokeMethod: Method,
  private val closeMethod: Method
) {
  private fun call(method: Method, vararg args: Any?): Any? {
    try {
      return method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
      throw e.targetException
    }
  }

  fun configure(values: FloatArray): Long = (call(configureMethod, values) as Number).toLong()
  fun command(op: Int, a: Long, b: Long, c: Long, d: Long): Long =
    (call(commandMethod, op, a, b, c, d) as Number).toLong()
  fun load(direction: Int, rate: Int, channels: Int, pcm: ShortArray): Long =
    (call(loadMethod, direction, rate, channels, pcm) as Number).toLong()
  fun status(values: FloatArray): Boolean = call(statusMethod, values) as Boolean
  fun drain(tap: Int, metadata: LongArray, pcm: FloatArray): Int =
    (call(drainMethod, tap, metadata, pcm) as Number).toInt()
  fun revoke() { call(revokeMethod) }
  fun close() { call(closeMethod) }

  companion object {
    private const val CLASS_NAME = "org.signal.ringrtc.MockCallSession"

    fun create(context: Context, configuration: Any): PatchedMockCallSession {
      val type = try {
        Class.forName(CLASS_NAME)
      } catch (e: ClassNotFoundException) {
        throw IllegalStateException("The patched local call engine is not packaged in this build", e)
      }
      val create = type.getMethod("create", Context::class.java, configuration.javaClass)
      val target = try {
        create.invoke(null, context, configuration)
      } catch (e: InvocationTargetException) {
        throw e.targetException
      } ?: throw IllegalStateException("The patched local call engine returned no session")

      return PatchedMockCallSession(
        target = target,
        configureMethod = type.getMethod("configure", FloatArray::class.java),
        commandMethod = type.getMethod(
          "command",
          Int::class.javaPrimitiveType,
          Long::class.javaPrimitiveType,
          Long::class.javaPrimitiveType,
          Long::class.javaPrimitiveType,
          Long::class.javaPrimitiveType
        ),
        loadMethod = type.getMethod(
          "load",
          Int::class.javaPrimitiveType,
          Int::class.javaPrimitiveType,
          Int::class.javaPrimitiveType,
          ShortArray::class.java
        ),
        statusMethod = type.getMethod("status", FloatArray::class.java),
        drainMethod = type.getMethod("drain", Int::class.javaPrimitiveType, LongArray::class.java, FloatArray::class.java),
        revokeMethod = type.getMethod("revoke"),
        closeMethod = type.getMethod("close")
      )
    }
  }
}

/** Production adapter: the factory and ADM are the calling stack's existing
 * implementation. A LabNativePort is not a standalone AudioRecord/MediaPlayer. */
internal object LabAndroidHardware {
  fun environment(context:Context,token:LabToken)=LabControllerEnvironment(
    directory=File(context.noBackupFilesDir,"mock-call"),
    acquire={
      val configuration=RingRtcDynamicConfiguration.getAudioConfig()
      val route=LabAndroidRoute(context)
      val session=try{PatchedMockCallSession.create(context,configuration)}catch(e:Exception){
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
