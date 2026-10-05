#!/usr/bin/env python3
"""Canonicalize pinned DeepFilter model assets before alignment/signing.

Some Android asset-packaging paths expand .gz inputs. The app requires the
original hash-pinned archives. Never alter DEX, resources, native code, other
assets, or an existing signed APK. A corrupt existing model is an error, not
repaired.
"""
import argparse,copy,gzip,hashlib,json,os,re,shutil,tempfile,zipfile
from pathlib import Path
from prepare_models import validate_archive

def sha(data):return hashlib.sha256(data).hexdigest()

def _locked_models(lock:Path):
 raw=json.loads(Path(lock).read_text())
 if isinstance(raw,dict) and 'models' in raw:
  models=raw['models']
 else:
  models=[raw]
 if not isinstance(models,list):raise ValueError('Invalid model lock')
 return models

def _payloads(models:Path,lock:Path):
 models=Path(models);result={}
 for item in _locked_models(Path(lock)):
  if not isinstance(item,dict):raise ValueError('Invalid model lock entry')
  asset=item.get('asset','')
  name='assets/deepfilter/'+asset
  if '/' in asset or not asset.endswith('.tar.gz'):raise ValueError('Unexpected model asset name')
  if name in result:raise ValueError('Duplicate model asset: '+name)
  data=(models/asset).read_bytes()
  validate_archive(data,item)
  result[name]=data
 return result

def finalize(source:Path,destination:Path,models:Path,lock:Path,extra_models:Path|None=None,extra_lock:Path|None=None)->dict:
 source=Path(source);destination=Path(destination);models=Path(models)
 if source.resolve()==destination.resolve() or destination.exists():raise ValueError('Use a new unsigned output path')
 if (extra_models is None)!=(extra_lock is None):raise ValueError('Extra models and lock must be supplied together')
 payloads=_payloads(models,Path(lock))
 if extra_models is not None:
  for name,data in _payloads(Path(extra_models),Path(extra_lock)).items():
   if name in payloads:raise ValueError('Duplicate model asset: '+name)
   payloads[name]=data
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
 p.add_argument('--extra-models',type=Path);p.add_argument('--extra-lock',type=Path)
 a=p.parse_args()
 if (a.extra_models is None)!=(a.extra_lock is None):p.error('--extra-models and --extra-lock must be supplied together')
 print(json.dumps(finalize(a.input,a.output,a.models,a.lock,a.extra_models,a.extra_lock),indent=2))
