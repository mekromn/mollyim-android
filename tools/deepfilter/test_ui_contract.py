#!/usr/bin/env python3
"""Source contracts only; the actual Compose instrumentation suite is separate."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
U=ROOT/'app/src/main/java/org/thoughtcrime/securesms/components/webrtc/v2'
class UiContracts(unittest.TestCase):
    def test_call_entry_and_independent_visibility(self):
        panel=U/'CallAudioControls.kt';self.assertTrue(panel.exists(),'Two-tab call audio UI is not implemented')
        self.assertIn('CallAudioControls(', (U/'CallControls.kt').read_text())
        self.assertIn('onCallAudioSheetDisplayChanged', (U/'CallControls.kt').read_text())
        self.assertIn('isDisplayingCallAudioSheet', (U/'CallScreenState.kt').read_text())
        self.assertIn('isDisplayingCallAudioSheet = displayed', (U/'ComposeCallScreenMediator.kt').read_text())
        text=panel.read_text();self.assertIn('Direction.RECEIVED',text);self.assertIn('Direction.SENT',text)
        self.assertIn('ReceivedAudioEffects(',text);self.assertIn('delay(100)',text)
    def test_all_visible_controls_and_received_effects_retained(self):
        controls=U/'DeepFilterSection.kt';self.assertTrue(controls.exists(),'DeepFilterNet controls are not implemented')
        strings=ROOT/'app/src/main/res/values/call_denoise.xml';self.assertTrue(strings.exists())
        values={n.attrib['name']:n.text for n in ET.parse(strings).getroot().findall('string')}
        self.assertEqual(values['call_audio_title'],'Call audio')
        self.assertEqual(values['denoise_received'],'Received — What you hear')
        self.assertEqual(values['denoise_sent'],'Sent — Your microphone')
        self.assertEqual(values['received_effects_master'],'EQ, compressor, gain and limiter')
        text=controls.read_text()
        for word in ('attenuationDb','postFilter','beta','minSnr','erbSnr','dfSnr','selectedPreset','onBypass','onRetry','onReset'):
            self.assertIn(word,text)
        old=(U/'IncomingAudioControls.kt').read_text();self.assertIn('internal fun ReceivedAudioEffects',old)
        for section in ('settings.eq','settings.threshold','settings.ceiling','settings.gain'):
            self.assertIn(section,old)
if __name__=='__main__':unittest.main()
