from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class AndroidBuilderTest(unittest.TestCase):
 def test_cross_build_keeps_precision_unwind_and_api(self):
  p=ROOT/'tools/deepfilter/build_runtime.sh'
  self.assertTrue(p.exists(),'ARM runtime builder missing')
  s=p.read_text()
  for token in ['1.88.0','28.0.13004108','aarch64-linux-android27-clang','--locked','max-page-size=16384','verify_runtime.py']:
   self.assertIn(token,s)
  self.assertNotIn('panic=abort',s)
  self.assertNotIn('fast-math',s)
 def test_binary_verifier_rejects_host_library(self):
  p=ROOT/'tools/deepfilter/verify_runtime.py'
  self.assertTrue(p.exists(),'Actual ARM ELF verification missing')
  import subprocess
  result=subprocess.run(['python3',str(p),str(ROOT/'out/runtime-host/libmolly_deepfilter.so')],capture_output=True,text=True)
  self.assertNotEqual(result.returncode,0)
  self.assertIn('AArch64',result.stderr)
if __name__=='__main__':unittest.main()
