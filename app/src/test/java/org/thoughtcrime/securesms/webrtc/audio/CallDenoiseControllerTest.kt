/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio

import org.junit.Assert.*
import org.junit.Test

class CallDenoiseControllerTest {
  private class Native : DenoiseNative {
    var ready = true
    val applied = mutableMapOf<Direction, DenoiseSettings>()
    var calls = 0
    override fun available() = ready
    override fun apply(direction: Direction, settings: DenoiseSettings): Boolean { calls++; applied[direction] = settings; return true }
    override fun bypass(direction: Direction, value: Boolean) = Unit
    override fun retry(direction: Direction) = Unit
  }
  private class Store : DenoiseStore {
    var value = DenoisePair()
    var loads = 0
    var fail = false
    override fun load(): DenoisePair { loads++; check(!fail); return value }
    override fun save(value: DenoisePair) { this.value = value }
  }
  @Test fun late_asset_completion_uses_latest_disabled_settings() {
    val native = Native(); val store = Store(); val requests = mutableListOf<Long>()
    val controller = DenoiseCoordinator(native, store, { requests.add(it) }, {})
    controller.initialize()
    controller.update(Direction.SENT, DenoiseSettings(enabled = true))
    assertEquals(1, requests.size)
    assertFalse(native.applied.getValue(Direction.SENT).enabled)
    controller.update(Direction.SENT, DenoiseSettings(enabled = false))
    controller.assetsCompleted(requests.single(), true)
    assertFalse(native.applied.getValue(Direction.SENT).enabled)
    assertFalse(store.value.sent.enabled)
    val calls = native.calls
    controller.initialize()
    assertEquals(calls, native.calls)
    assertEquals(1, store.loads)
  }
  @Test fun missing_native_or_storage_cannot_enable_a_filter() {
    val native = Native().apply { ready = false }; val store = Store().apply { fail = true }
    val requests = mutableListOf<Long>()
    val controller = DenoiseCoordinator(native, store, { requests.add(it) }, {})
    controller.initialize()
    assertFalse(controller.snapshot().storageAvailable)
    assertFalse(controller.snapshot().nativeAvailable)
    assertEquals(0, native.calls)
    assertTrue(requests.isEmpty())
    assertEquals(DenoisePair(), controller.snapshot().settings)
  }
  @Test fun reset_and_edit_do_not_overwrite_other_direction() {
    val native = Native(); val store = Store(); val requests = mutableListOf<Long>()
    val received = DenoiseSettings(model = Model.LOW_LATENCY, attenuationDb = 12f)
    store.value = DenoisePair(received = received)
    val controller = DenoiseCoordinator(native, store, { requests.add(it) }, {})
    controller.initialize()
    controller.update(Direction.SENT, DenoiseSettings().preset(Preset.STRONG))
    controller.reset(Direction.SENT)
    assertEquals(received, store.value.received)
    assertEquals(DenoiseSettings(), store.value.sent)
    assertTrue(requests.isEmpty())
  }
}
