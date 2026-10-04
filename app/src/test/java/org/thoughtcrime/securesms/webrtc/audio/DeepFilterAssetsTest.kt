/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DeepFilterAssetsTest {
  @get:Rule val temporary = TemporaryFolder()
  private fun asset(bytes: ByteArray) = ModelAsset("model.tar.gz", bytes.size.toLong(),
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
  @Test fun verified_model_is_atomic_and_reused() {
    val directory = temporary.newFolder(); val bytes = "verified model fixture".toByteArray(); var opens = 0
    val store = ModelAssetStore(directory, { opens++; ByteArrayInputStream(bytes) }, listOf(asset(bytes)))
    val files = store.install()
    assertArrayEquals(bytes, files.single().readBytes())
    store.install()
    assertEquals(1, opens)
    assertEquals(1, directory.listFiles()!!.size)
  }
  @Test fun corrupt_replacement_never_overwrites_existing_file() {
    val directory = temporary.newFolder(); val expected = "correct bytes".toByteArray(); val old = "previous copy".toByteArray()
    val original = File(directory, "model.tar.gz").apply { writeBytes(old) }
    val store = ModelAssetStore(directory, { ByteArrayInputStream("bad".toByteArray()) }, listOf(asset(expected)))
    try { store.install(); fail("Corrupt source accepted") } catch (_: IOException) { }
    assertArrayEquals(old, original.readBytes())
    assertEquals(1, directory.listFiles()!!.size)
  }

  @Test fun bundled_manifest_pins_mobile_fused_model() {
    assertEquals(3, ModelAssetStore.MODELS.size)
    val mobile = ModelAssetStore.MODELS.single { it.name == "DeepFilterNet3_onnx_mobile.tar.gz" }
    assertEquals(7_984_565L, mobile.bytes)
    assertEquals("5600b6857117ecc7cf460b8ec4841963bfa6d718921d424d42dea5d3d37a8c32", mobile.sha256)
  }
}
