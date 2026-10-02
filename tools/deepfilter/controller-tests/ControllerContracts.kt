package org.thoughtcrime.securesms.webrtc.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

private class FakeNative : DenoiseNative {
  var ready = true
  val applied = mutableMapOf<Direction, DenoiseSettings>()
  val bypassed = mutableMapOf<Direction, Boolean>()
  var retries = 0
  override fun available() = ready
  override fun apply(direction: Direction, settings: DenoiseSettings): Boolean { if (!ready) return false; applied[direction] = settings; return true }
  override fun bypass(direction: Direction, value: Boolean) { bypassed[direction] = value }
  override fun retry(direction: Direction) { retries++ }
}
private class MemoryStore : DenoiseStore {
  var value = DenoisePair()
  var writes = 0
  override fun load() = value
  override fun save(value: DenoisePair) { this.value = value; writes++ }
}
private fun checkCase(name: String, action: () -> Unit) { action(); println("$name PASS") }
fun main() {
  checkCase("disabled_edit_never_loads_models") {
    val native = FakeNative(); val store = MemoryStore(); val requests = mutableListOf<Long>()
    val c = DenoiseCoordinator(native, store, { requests += it }, {})
    c.initialize(); c.update(Direction.SENT, DenoiseSettings(attenuationDb = 12f), false)
    check(requests.isEmpty()); check(c.snapshot().settings.received == DenoiseSettings()); check(store.writes == 0)
    c.save(); check(store.value.sent.attenuationDb == 12f)
  }
  checkCase("background_init_idempotent_preserves_saved_settings") {
    val native = FakeNative(); val store = MemoryStore(); store.value = DenoisePair(sent = DenoiseSettings(enabled = true))
    val requests = mutableListOf<Long>(); val c = DenoiseCoordinator(native, store, { requests += it }, {})
    c.initialize(); c.initialize(); check(requests.size == 1)
    check(native.applied[Direction.SENT]?.enabled == false)
    c.assetsCompleted(requests.single(), true); check(native.applied[Direction.SENT]?.enabled == true)
    c.initialize(); check(requests.size == 1); check(c.snapshot().settings.sent.enabled)
  }
  checkCase("disable_while_assets_loading_never_reenables") {
    val n = FakeNative(); val requests = mutableListOf<Long>(); val c = DenoiseCoordinator(n, MemoryStore(), { requests += it }, {})
    c.initialize(); c.update(Direction.SENT, DenoiseSettings(enabled = true)); c.update(Direction.SENT, DenoiseSettings())
    c.assetsCompleted(requests.single(), true); check(n.applied[Direction.SENT]?.enabled == false)
  }
  checkCase("missing_jni_waits_for_native_initialization") {
    val n = FakeNative(); n.ready = false; val requests = mutableListOf<Long>(); val c = DenoiseCoordinator(n, MemoryStore(), { requests += it }, {})
    c.initialize(); c.update(Direction.RECEIVED, DenoiseSettings(enabled = true)); check(requests.isEmpty())
    check(!c.snapshot().nativeAvailable); n.ready = true; c.initialize(); check(requests.size == 1)
    c.assetsCompleted(requests.single(), true); check(n.applied[Direction.RECEIVED]?.enabled == true)
  }
  checkCase("asset_failure_requires_explicit_retry") {
    val n = FakeNative(); val requests = mutableListOf<Long>(); val c = DenoiseCoordinator(n, MemoryStore(), { requests += it }, {})
    c.initialize(); c.update(Direction.SENT, DenoiseSettings(enabled = true)); c.assetsCompleted(requests.last(), false)
    repeat(10) { c.initialize() }; check(requests.size == 1); check(n.applied[Direction.SENT]?.enabled == false)
    c.retry(Direction.SENT); check(requests.size == 2); c.assetsCompleted(requests.first(), true)
    check(!c.snapshot().assetsReady); c.assetsCompleted(requests.last(), true); check(c.snapshot().assetsReady)
  }
  checkCase("reset_is_local_and_no_bypass_persistence") {
    val n = FakeNative(); val store = MemoryStore(); val requests = mutableListOf<Long>(); val c = DenoiseCoordinator(n, store, { requests += it }, {})
    c.initialize(); c.update(Direction.RECEIVED, DenoiseSettings(enabled = true, attenuationDb = 12f)); c.assetsCompleted(requests.single(), true)
    c.update(Direction.SENT, DenoiseSettings(enabled = true, model = Model.LOW_LATENCY))
    c.bypass(Direction.SENT, true); c.reset(Direction.SENT)
    check(c.snapshot().settings.sent == DenoiseSettings()); check(c.snapshot().settings.received.attenuationDb == 12f); check(n.bypassed[Direction.SENT] == false)
    check(store.value == c.snapshot().settings)
  }
  checkCase("store_failure_does_not_change_ordinary_call") {
    val store = object : DenoiseStore { override fun load(): DenoisePair = throw IllegalStateException("locked"); override fun save(value: DenoisePair) { throw IllegalStateException("locked") } }
    val n = FakeNative(); var loads = 0; val c = DenoiseCoordinator(n, store, { loads++ }, {})
    c.initialize(); c.save(); check(!c.snapshot().settings.sent.enabled); check(!c.snapshot().storageAvailable); check(loads == 0)
  }
  val bytes = ByteArray(200) { it.toByte() }
  val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  checkCase("atomic_asset_publish_and_reuse") {
    val dir = Files.createTempDirectory("molly-assets").toFile()
    try {
      var opened = 0
      val assets = ModelAssetStore(dir, { opened++; ByteArrayInputStream(bytes) }, listOf(ModelAsset("model.tar.gz", bytes.size.toLong(), hash)))
      check(assets.install().single().readBytes().contentEquals(bytes)); check(opened == 1)
      assets.install(); check(opened == 1)
    } finally { dir.deleteRecursively() }
  }
  checkCase("interrupted_asset_copy_never_publishes_partial") {
    val dir = Files.createTempDirectory("molly-assets").toFile()
    try {
      val assets = ModelAssetStore(dir, { object : InputStream() { var count = 0; override fun read(): Int { if (count++ == 10) throw IOException("interrupted"); return 0 } } }, listOf(ModelAsset("model.tar.gz", bytes.size.toLong(), hash)))
      check(runCatching { assets.install() }.isFailure); check(!File(dir, "model.tar.gz").exists()); check(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    } finally { dir.deleteRecursively() }
  }
  checkCase("corrupt_asset_rejected_and_repair_keeps_original_until_verified") {
    val dir = Files.createTempDirectory("molly-assets").toFile()
    try {
      File(dir, "model.tar.gz").writeText("original-corrupt")
      val assets = ModelAssetStore(dir, { ByteArrayInputStream(ByteArray(200)) }, listOf(ModelAsset("model.tar.gz", bytes.size.toLong(), hash)))
      check(runCatching { assets.install() }.isFailure); check(File(dir, "model.tar.gz").readText() == "original-corrupt")
    } finally { dir.deleteRecursively() }
  }
}
