#!/usr/bin/env python3
"""Source contracts for local-only navigation; rendering is separate instrumentation."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'app/src/main/java/org/thoughtcrime/securesms'
class ScreenContracts(unittest.TestCase):
 def test_mock_uses_production_call_screen_and_controls(self):
  p=A/'components/webrtc/v2/MockCallScreen.kt'
  self.assertTrue(p.is_file(),'Mock call screen is missing')
  s=p.read_text()
  for x in ['CallScreen(', 'callAudioSession = controller', 'LabStatsPanel(', 'LOCAL TEST', 'Speakerphone echo test',
            'onMicChanged', 'onEndCallPressed', 'onAudioOutputChanged31']:
   self.assertIn(x,s)
  # Lab-specific transport/diagnostic chrome may overlay the real call screen,
  # but the core call buttons must come from production CallControls.
  self.assertNotIn('Surface(Modifier.fillMaxSize()',s)
  self.assertNotIn('ToggleMicButton(',s)
  self.assertNotIn('HangupButton(',s)
  for x in ['Recipient.self()', 'startOutgoingAudioCall', 'CallIntent', 'AudioRecord(', 'MediaPlayer(']:
   self.assertNotIn(x,s)

  controls=(A/'components/webrtc/v2/CallControls.kt').read_text()
  self.assertIn('callAudioSession: CallAudioSession = ProductionCallAudioSession',controls)
  self.assertIn('session = callAudioSession',controls)
  self.assertIn('statsHeader = callAudioStatsHeader',controls)
  self.assertIn('callControlsExtraContent: @Composable () -> Unit = {}',controls)
  self.assertIn('callControlsExtraContent()',controls)

  call_screen=(A/'components/webrtc/v2/CallScreen.kt').read_text()
  self.assertIn('callAudioSession: CallAudioSession = ProductionCallAudioSession',call_screen)
  self.assertIn('callScreenOverlay: @Composable BoxScope.() -> Unit = {}',call_screen)
  self.assertIn('callControlsExtraContent: @Composable () -> Unit = {}',call_screen)
  self.assertIn('callAudioSession = callAudioSession',call_screen)
  self.assertIn('callControlsExtraContent = callControlsExtraContent',call_screen)
  self.assertIn('callScreenOverlay()',call_screen)

  mock=(A/'components/webrtc/v2/MockCallScreen.kt').read_text()
  overlay=mock[mock.index('private fun LabNativeOverlay'):mock.index('@OptIn(ExperimentalLayoutApi::class)',mock.index('private fun LabNativeOverlay'))]
  self.assertNotIn('LabStatsPanel(',overlay)
  self.assertNotIn('Text("Record")',overlay)
  self.assertNotIn('Text(if (view.playing) "Stop" else "Play")',overlay)
  self.assertIn('callControlsExtraContent = {',mock)
  self.assertIn('LabTestTransportControls(',mock)
  self.assertIn('val canPlay =',mock)
  self.assertIn('enabled = !view.retired && (view.playing || canPlay)',mock)

 def test_echo_record_keeps_explicit_both_mode_across_permission(self):
  screen=(A/'components/webrtc/v2/MockCallScreen.kt').read_text()
  activity=(A/'webrtc/audio/mock/MockCallLabActivity.kt').read_text()
  self.assertIn('onRecord(LabMode.BOTH)',screen)
  self.assertIn('pendingRecordMode',activity)
  self.assertNotIn('if(c.view.value.mode==LabMode.RECEIVED)',activity)

 def test_nonexported_internal_entry(self):
  s=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
  self.assertIn('webrtc.audio.mock.MockCallLabActivity',s)
  import re
  m=re.search(r'<activity[^>]*MockCallLabActivity[^>]*/>',s,re.S)
  self.assertIsNotNone(m);self.assertIn('android:exported="false"',m[0])
  h=(A/'components/settings/app/help/HelpSettingsFragment.kt').read_text()
  self.assertIn('MockCallLabActivity',h)

 def test_compact_stats_keep_timing_misses_and_levels_visible(self):
  s=(A/'components/webrtc/v2/LabStatsPanel.kt').read_text().split('@OptIn',1)[0]
  self.assertNotIn('if(!compact)',s,'Pinned stats must not hide processing time, faults or meters')
  for x in ('d.meanMs','d.p95Ms','d.misses','d.inputPeak','d.outputPeak'):
   self.assertIn(x,s)
if __name__=='__main__':unittest.main()
