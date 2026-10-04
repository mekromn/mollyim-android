/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ModelAsset(val name: String, val bytes: Long, val sha256: String)

/** Bundled immutable models only. Run on IO, never on UI or audio callbacks. */
internal class ModelAssetStore(
  private val directory: File,
  private val openBundled: (String) -> InputStream,
  private val models: List<ModelAsset> = MODELS
) {
  @Synchronized fun install(): List<File> {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Private model directory unavailable")
    return models.map { model ->
      require(model.name.matches(Regex("[A-Za-z0-9_.-]+")) && model.bytes in 1..40_000_000)
      val destination = File(directory, model.name)
      require(destination.canonicalFile.parentFile == directory.canonicalFile)
      if (!verified(destination, model)) {
        val temporary = File.createTempFile("df-model-", ".tmp", directory)
        try {
          openBundled(model.name).use { input ->
            FileOutputStream(temporary).use { output ->
              val buffer = ByteArray(64 * 1024)
              var total = 0L
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Model source made no progress")
                total += count
                if (total > model.bytes) throw IOException("Bundled model length mismatch")
                output.write(buffer, 0, count)
              }
              if (total != model.bytes) throw IOException("Incomplete bundled model")
              output.fd.sync()
            }
          }
          if (!verified(temporary, model)) throw IOException("Bundled model checksum mismatch")
          Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
          temporary.delete()
        }
      }
      destination
    }
  }

  private fun verified(file: File, model: ModelAsset): Boolean {
    if (!file.isFile || file.length() != model.bytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) return false
        digest.update(buffer, 0, count)
      }
    }
    return MessageDigest.isEqual(digest.digest(), model.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
  }

  companion object {
    val MODELS = listOf(
      ModelAsset("DeepFilterNet3_onnx.tar.gz", 7_983_136L, "c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616"),
      ModelAsset("DeepFilterNet3_ll_onnx.tar.gz", 36_359_660L, "5998e58e8ba0e09bb76986ef97b84afa065a571ef282d4a1222f341e3251cf3a"),
      ModelAsset("DeepFilterNet3_onnx_mobile.tar.gz", 7_984_565L, "5600b6857117ecc7cf460b8ec4841963bfa6d718921d424d42dea5d3d37a8c32")
    )
  }
}
