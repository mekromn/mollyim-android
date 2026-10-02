"""Release gates use actual assets/ELF; synthetic APKs test fail-closed handling."""
import importlib.util
from pathlib import Path
import tempfile, unittest, zipfile
ROOT=Path(__file__).resolve().parents[2]
class ReleaseTests(unittest.TestCase):
 def module(self):
  path=Path(__file__).with_name('verify_release.py')
  self.assertTrue(path.exists(),'Release payload verifier is missing')
  spec=importlib.util.spec_from_file_location('verify_release',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
 def test_empty_and_previous_apk_not_accepted(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'empty.apk'
   with zipfile.ZipFile(p,'w') as z:z.writestr('AndroidManifest.xml',b'fixture')
   with self.assertRaises(ValueError):m.verify_payload(p,ROOT/'tools/deepfilter/models.lock.json')
 def test_version_and_debug_guard(self):
  m=self.module()
  good="package: name='com.mekromn.mollyaudio' versionCode='171906' versionName='8.19.2-4'\nsdkVersion:'27'\ntargetSdkVersion:'35'\nnative-code: 'arm64-v8a'\n"
  self.assertEqual(m.verify_badging(good)['version_code'],171906)
  for bad in (good.replace('171906','171905'),good+'application-debuggable\n',good.replace('com.mekromn.mollyaudio','org.thoughtcrime.securesms'),good.replace('arm64-v8a','x86_64')):
   with self.assertRaises(ValueError):m.verify_badging(bad)
 def test_jni_requires_definitions_not_references(self):
  m=self.module()
  # A descriptor/name appearing only in strings does not prove a class method survived.
  with self.assertRaises(ValueError):m.verify_dexdump("CallDenoiseBridge nativeVersion ()I",m.JNI)
 def test_manifest_input_hashes(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'x').write_text('old')
   with self.assertRaises(ValueError):m.verify_inputs(p,{'x':'0'*64})
 def test_licenses_and_version_in_source(self):
  self.assertIn('currentHotfixVersion = 2',(ROOT/'app/build.gradle.kts').read_text())
  notice=ROOT/'app/src/main/res/raw/deepfilter_notices.txt'
  self.assertTrue(notice.exists(),'DeepFilterNet attribution not packaged')
  self.assertIn('Hendrik Schröter',notice.read_text())
  self.assertIn('R.raw.deepfilter_notices',(ROOT/'app/src/main/java/org/thoughtcrime/securesms/components/settings/app/help/LicenseFragment.kt').read_text())
if __name__=='__main__':unittest.main()
