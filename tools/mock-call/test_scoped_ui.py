#!/usr/bin/env python3
"""Source boundary checks, not rendered Android UI tests."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
UI=ROOT/'app/src/main/java/org/thoughtcrime/securesms/components/webrtc/v2'
class ScopedUiTests(unittest.TestCase):
 def test_call_sheet_edits_and_exit_use_selected_session(self):
  source=(UI/'CallAudioControls.kt').read_text()
  self.assertIn('session: CallAudioSession = ProductionCallAudioSession',source)
  self.assertNotIn('CallDenoiseController.',source)
  self.assertNotIn('IncomingAudioController.',source)
  self.assertIn('session.save()',source)
  self.assertIn('statsHeader()',source)
 def test_received_content_and_slider_finish_never_write_singletons(self):
  source=(UI/'IncomingAudioControls.kt').read_text().split('internal fun ReceivedAudioEffects',1)[1]
  self.assertNotIn('IncomingAudioController',source)
  self.assertNotIn('IncomingAudioBridge',source)
  self.assertIn('session.readEffectsMeters',source)
  self.assertIn('onValueChangeFinished = onFinished',source)
if __name__=='__main__':unittest.main()
