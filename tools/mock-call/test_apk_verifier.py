#!/usr/bin/env python3
import unittest
try:
 from verify_apk import badging, manifest, verify_mock_dex
except ImportError:
 badging=manifest=verify_mock_dex=None

class PackageTests(unittest.TestCase):
 def test_update_identity(self):
  self.assertIsNotNone(badging,'Mock APK verifier is missing')
  text="package: name='com.mekromn.mollyaudio' versionCode='171907' versionName='8.19.2-4'\nminSdkVersion: '27'\ntargetSdkVersion: '35'\nnative-code: 'arm64-v8a'\n"
  self.assertEqual(badging(text)['version_code'],171907)
  for wrong in [text.replace('171907','171906'),text.replace('mollyaudio','other'),text+'application-debuggable\n',text.replace("'arm64-v8a'","'arm64-v8a' 'x86'"),text.replace("'27'","'26'")]:
   with self.assertRaises(ValueError):badging(wrong)
 def test_private_activity(self):
  self.assertIsNotNone(manifest)
  s='''E: manifest (line=1)
  E: application (line=3)
    E: activity (line=4)
      A: android:name(0x01010003)="org.thoughtcrime.securesms.webrtc.audio.mock.MockCallLabActivity" (Raw: "org.thoughtcrime.securesms.webrtc.audio.mock.MockCallLabActivity")
      A: android:exported(0x01010010)=(type 0x12)0x0
    E: activity (line=8)
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
'''
  manifest(s)
  for wrong in [s.replace('(type 0x12)0x0','(type 0x12)0xffffffff'),s.replace('MockCallLabActivity','OtherActivity')]:
   with self.assertRaises(ValueError):manifest(wrong)
 def test_missing_mock_native_defs_rejected(self):
  self.assertIsNotNone(verify_mock_dex)
  with self.assertRaises(ValueError):verify_mock_dex('')
if __name__=='__main__':unittest.main()
