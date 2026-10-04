/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import android.content.Context
import java.io.File
import java.io.IOException

internal object DeepFilterAssets {
  /** Called on Dispatchers.IO. Files are copied once, verified before native use. */
  fun install(context: Context): Boolean {
    val directory = File(context.noBackupFilesDir, "deepfilter-v1")
    val files = ModelAssetStore(directory, { context.assets.open("deepfilter/$it") }).install()
    val runtime = File(context.applicationInfo.nativeLibraryDir, "libmolly_deepfilter.so")
    if (!runtime.isFile || files.size != 3) throw IOException("Packaged native denoiser unavailable")
    return CallDenoiseBridge.paths(runtime.absolutePath, files[0].absolutePath, files[1].absolutePath, files[2].absolutePath)
  }
}
