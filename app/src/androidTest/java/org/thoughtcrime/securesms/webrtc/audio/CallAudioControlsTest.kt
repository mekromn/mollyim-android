package org.thoughtcrime.securesms.webrtc.audio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.components.webrtc.v2.DeepFilterSection

/** Does not create a call, request microphone access, or mutate account settings. */
class CallAudioControlsTest {
  @get:Rule val compose = createComposeRule()

  @Test fun preset_does_not_enable_and_reset_is_local() {
    val received = DenoiseSettings(enabled = true, attenuationDb = 12f)
    val sent = mutableStateOf(DenoiseSettings())
    compose.setContent {
      MaterialTheme {
        DeepFilterSection(Direction.SENT, sent.value, DenoiseUiSnapshot(nativeAvailable = true), DenoiseStatus(),
          onUpdate = { settings, _ -> sent.value = settings }, onSave = {}, onBypass = {}, onRetry = {},
          onReset = { sent.value = DenoiseSettings() })
      }
    }
    compose.onNodeWithTag("denoise-sent-preset-strong").performClick()
    compose.onNodeWithTag("denoise-sent-enable").assertIsOff()
    compose.runOnIdle { assertFalse(sent.value.enabled); assertEquals(100f, sent.value.attenuationDb, 0f); assertEquals(12f, received.attenuationDb, 0f) }
  }
}
