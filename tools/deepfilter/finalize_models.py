#!/usr/bin/env python3
"""Canonicalize only the two pinned model assets before alignment/signing.

Some asset-packaging paths expand .gz inputs. The app requires the original,
hash-pinned archives. Never alter DEX, resources, native code, other assets,
or an existing signed APK. A corrupt existing model is an error, not repaired.
"""
import argparse,copy,gzip,hashlib,json,os,re,shutil,tempfile,zipfile
from pathlib import Path
from prepare_models import validate_archive

def sha(data):return hashlib.sha256(data).hexdigest()
def finalize(source:Path,destination:Path,models:Path,lock:Path)->dict:
 source=Path(source);destination=Path(destination);models=Path(models)
 if source.resolve()==destination.resolve() or destination.exists():raise ValueError('Use a new unsigned output path')
 expected=json.loads(Path(lock).read_text())['models'];payloads={}
 for item in expected:
  name='assets/deepfilter/'+item['asset']
  if '/' in item['asset'] or not item['asset'].endswith('.tar.gz'):raise ValueError('Unexpected model asset name')
  data=(models/item['asset']).read_bytes();validate_archive(data,item);payloads[name]=data
 report={'source_apk_sha256':sha(source.read_bytes()),'added_models':[],'removed_expanded_aliases':[]}
 destination.parent.mkdir(parents=True,exist_ok=True)
 temp=None
 try:
  with zipfile.ZipFile(source) as src:
   names=src.namelist()
   if len(names)!=len(set(names)) or src.testzip():raise ValueError('Invalid or duplicate archive entries')
   if any(re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC)',n,re.I) for n in names):raise ValueError('Input must be unsigned')
   with source.open('rb') as f:
    if src.start_dir>=16:
     f.seek(src.start_dir-16)
     if f.read(16)==b'APK Sig Block 42':raise ValueError('Input contains an APK signing block')
   remove=set()
   for name,data in payloads.items():
    if name in names:
     if src.read(name)!=data:raise ValueError('Existing model differs from pinned archive: '+name)
    else:report['added_models'].append(name)
    alias=name[:-3]
    if alias in names:
     existing=src.read(alias)
     if existing!=data and existing!=gzip.decompress(data):raise ValueError('Unexpected expanded model alias: '+alias)
     remove.add(alias);report['removed_expanded_aliases'].append(alias)
   if not report['added_models'] and not remove:
    shutil.copyfile(source,destination)
   else:
    with tempfile.NamedTemporaryFile(prefix='.models-',suffix='.apk',dir=destination.parent,delete=False) as f:temp=Path(f.name)
    with zipfile.ZipFile(temp,'w',allowZip64=False) as dst:
     dst.comment=src.comment
     for info in src.infolist():
      if info.filename not in remove:dst.writestr(copy.copy(info),src.read(info.filename))
     for name in report['added_models']:
      info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_STORED;info.external_attr=0o100644<<16
      dst.writestr(info,payloads[name])
    with zipfile.ZipFile(temp) as dst:
     if dst.testzip():raise ValueError('Final archive CRC failure')
     for info in src.infolist():
      if info.filename not in remove and dst.read(info.filename)!=src.read(info.filename):raise ValueError('Unrelated APK content changed')
     for name,data in payloads.items():
      if dst.read(name)!=data:raise ValueError('Final model differs from pinned bytes')
    os.replace(temp,destination);temp=None
  report['finalized_apk_sha256']=sha(destination.read_bytes())
  report['unchanged_compiled_code_and_other_assets']=True
  report['models']={n:sha(data) for n,data in payloads.items()}
  return report
 finally:
  if temp is not None:temp.unlink(missing_ok=True)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
 p.add_argument('--models',type=Path,required=True);p.add_argument('--lock',type=Path,default=Path(__file__).with_name('models.lock.json'))
 a=p.parse_args();print(json.dumps(finalize(a.input,a.output,a.models,a.lock),indent=2))
