#!/usr/bin/env python3
import unittest
try:
 from verify_apk import badging, baseline_badging, manifest, verify_mock_dex, verify_mobile_bytes
except ImportError:
 badging=baseline_badging=manifest=verify_mock_dex=verify_mobile_bytes=None

class PackageTests(unittest.TestCase):
 def test_update_identity(self):
  self.assertIsNotNone(badging,'Mock APK verifier is missing')
  text="package: name='com.mekromn.mollyaudio' versionCode='171909' versionName='8.19.2-4'\nminSdkVersion: '27'\ntargetSdkVersion: '35'\nnative-code: 'arm64-v8a'\n"
  self.assertEqual(badging(text)['version_code'],171909)
  for wrong in [text.replace('171909','171908'),text.replace('mollyaudio','other'),text+'application-debuggable\n',text.replace("'arm64-v8a'","'arm64-v8a' 'x86'"),text.replace("'27'","'26'")]:
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
 def test_sdk36_expanded_namespace_and_boolean(self):
  s='''E: manifest (line=1)
  E: application (line=3)
    E: activity (line=215)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="org.thoughtcrime.securesms.webrtc.audio.mock.MockCallLabActivity" (Raw: "org.thoughtcrime.securesms.webrtc.audio.mock.MockCallLabActivity")
      A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
      A: http://schemas.android.com/apk/res/android:excludeFromRecents(0x01010017)=true
    E: activity (line=220)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="OtherActivity"
      A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
'''
  self.assertFalse(manifest(s)['exported'])
  for wrong in [s.replace('=false','=true'),s.replace(':exported(0x01010010)=false',':exported(0x01010010)=unknown'),s.replace('android:exported','untrusted:exported'),s+s]:
   with self.assertRaises(ValueError):manifest(wrong)
 def test_missing_mock_native_defs_rejected(self):
  self.assertIsNotNone(verify_mock_dex)
  with self.assertRaises(ValueError):verify_mock_dex('')

 def test_mobile_model_payload_is_hash_pinned(self):
  import hashlib
  self.assertIsNotNone(verify_mobile_bytes)
  data=b'mobile fused fixture'
  spec={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'asset':'mobile.tar.gz','meta':{'sample_rate':48000,'hop':480,'fft':960,'lookahead':0,'df_order':5,'erb_bands':32,'df_bins':96,'intrinsic_delay':480}}
  result=verify_mobile_bytes(data,spec)
  self.assertEqual(result['sha256'],spec['sha256'])
  self.assertEqual(result['lookahead'],0)
  with self.assertRaises(ValueError):verify_mobile_bytes(data+b'x',spec)
  with self.assertRaises(ValueError):verify_mobile_bytes(data,{**spec,'sha256':'00'*32})
 def test_update_baseline_is_the_delivered_171908_build(self):
  self.assertIsNotNone(baseline_badging)
  good="package: name='com.mekromn.mollyaudio' versionCode='171908' versionName='8.19.2-4'\n"
  self.assertEqual(baseline_badging(good),171908)
  with self.assertRaises(ValueError):baseline_badging(good.replace('171908','171906'))
if __name__=='__main__':unittest.main()
