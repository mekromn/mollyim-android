"""Final packaging must preserve model bytes and every compiled entry."""
import gzip,hashlib,importlib.util,json,os,tempfile,unittest,zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
MODELS=Path(os.environ.get('DEEPFILTER_MODELS',str(HERE.parents[1]/'out/reference/models')))
class FinalizeTests(unittest.TestCase):
 def module(self):
  p=HERE/'finalize_models.py'
  self.assertTrue(p.is_file(),'Canonical model finalizer is missing')
  s=importlib.util.spec_from_file_location('finalize_models',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
 def setup_files(self,root,kind='missing'):
  lock=json.loads((HERE/'models.lock.json').read_text());src=root/'input.apk'
  with zipfile.ZipFile(src,'w',compression=zipfile.ZIP_DEFLATED) as z:
   z.writestr('classes.dex',b'unchanged compiled code');z.writestr('resources.arsc',b'unchanged resources')
   z.writestr('assets/deepfilter/notices.txt',b'license text')
   for item in lock['models']:
    data=(MODELS/item['asset']).read_bytes()
    if kind=='canonical':z.writestr('assets/deepfilter/'+item['asset'],data)
    if kind=='expanded':z.writestr('assets/deepfilter/'+item['asset'][:-3],gzip.decompress(data))
    if kind=='corrupt':z.writestr('assets/deepfilter/'+item['asset'],b'corrupt')
  return src,root/'output.apk',lock
 def test_missing_assets_are_added_without_changing_compiled_bytes(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,lock=self.setup_files(Path(d));r=m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertEqual(len(r['added_models']),2)
   with zipfile.ZipFile(src) as a,zipfile.ZipFile(out) as b:
    for n in a.namelist():self.assertEqual(a.read(n),b.read(n),n)
    for x in lock['models']:self.assertEqual(hashlib.sha256(b.read('assets/deepfilter/'+x['asset'])).hexdigest(),x['sha256'])
 def test_expanded_gzip_aliases_are_removed_only_when_exact(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,lock=self.setup_files(Path(d),'expanded');r=m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertEqual(len(r['removed_expanded_aliases']),2)
   with zipfile.ZipFile(out) as z:self.assertFalse(any(n.endswith('.tar') for n in z.namelist()))
 def test_correct_package_is_byte_identical(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,_=self.setup_files(Path(d),'canonical');m.finalize(src,out,MODELS,HERE/'models.lock.json');self.assertEqual(src.read_bytes(),out.read_bytes())
 def test_existing_corrupt_model_fails_closed(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,_=self.setup_files(Path(d),'corrupt')
   with self.assertRaises(ValueError):m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertFalse(out.exists())
 def test_signed_archive_is_rejected(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,_=self.setup_files(Path(d))
   with zipfile.ZipFile(src,'a') as z:z.writestr('META-INF/CERT.RSA',b'not unsigned')
   with self.assertRaises(ValueError):m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertFalse(out.exists())
 def test_duplicate_entry_is_rejected(self):
  import warnings
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,_=self.setup_files(Path(d))
   with warnings.catch_warnings():
    warnings.simplefilter('ignore')
    with zipfile.ZipFile(src,'a') as z:z.writestr('classes.dex',b'duplicate')
   with self.assertRaises(ValueError):m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertFalse(out.exists())
 def test_wrong_expanded_model_is_not_replaced(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   src,out,lock=self.setup_files(Path(d))
   with zipfile.ZipFile(src,'a') as z:z.writestr('assets/deepfilter/'+lock['models'][0]['asset'][:-3],b'unrecognized data')
   with self.assertRaises(ValueError):m.finalize(src,out,MODELS,HERE/'models.lock.json')
   self.assertFalse(out.exists())
if __name__=='__main__':unittest.main()
