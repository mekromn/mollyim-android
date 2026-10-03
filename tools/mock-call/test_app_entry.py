#!/usr/bin/env python3
"""Source guard tests; Android execution is an independent build/device gate."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
APP=ROOT/'app/src/main/java/org/thoughtcrime/securesms'
class EntryTests(unittest.TestCase):
 def test_required_start_paths_reserve_production_before_enqueuing(self):
  s=(APP/'service/webrtc/SignalCallManager.java').read_text()
  self.assertIn('MockCallLabRuntime.reserveRealCall()',s)
  self.assertIn('MockCallLabRuntime.awaitHardwareStop()',s)
  self.assertIn('MockCallLabRuntime.realStateChanged(',s)
  for name in ('startPreJoinCall','startOutgoingAudioCall','startOutgoingVideoCall','receivedOffer','onStartCall','onGroupCallRingUpdate','onLocalDeviceStateChanged'):
   start=s.index(' '+name+'(');end=s.find('\n  @Override',start+1)
   if end<0:end=len(s)
   section=s[start:end]
   self.assertIn('processCallStart(',section[:section.find('\n  public ',1) if '\n  public ' in section[1:] else len(section)],name)
 def test_local_router_never_unmutes_the_system_microphone(self):
  s=(APP/'webrtc/audio/SignalAudioManager.kt').read_text()
  self.assertTrue('if (localTestMode) return' in s)
  self.assertTrue('fun configureForLocalTest()' in s)
 def test_router_retirement_cancels_delayed_commands(self):
  s=(APP/'webrtc/audio/SignalAudioManager.kt').read_text()
  self.assertIn('fun shutdown(onStopped: Runnable?)',s)
  self.assertIn('handler.removeCallbacksAndMessages(null)',s)
  self.assertIn('onStopped?.run()',s)
  self.assertTrue('if (!localTestMode || state != State.UNINITIALIZED) stop(false)' in s)
if __name__=='__main__':unittest.main()
