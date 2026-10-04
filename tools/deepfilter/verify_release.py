#!/usr/bin/env python3
"""Validate packaged release content, JNI definitions and update identity.
Uses SDK aapt2/dexdump and apksigner, not filename or string-presence guesses.
"""
from pathlib import Path
import argparse, hashlib, json, re, struct, subprocess, tempfile, zipfile
import prepare_models
from package_native import GATE, BRIDGE, CPP, symbols

PACKAGE='com.mekromn.mollyaudio'
CERT='eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b'
JNI={
 'Lorg/thoughtcrime/securesms/webrtc/audio/CallDenoiseBridge;':{
  'nativeVersion':'()I','nativePaths':'(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z',
  'nativeApply':'(IZIFZFFFF)Z','nativeBypass':'(IZ)V','nativeRetry':'(I)V','nativeStatus':'(I[F)Z'},
 'Lorg/signal/ringrtc/CallDenoiseGate;':{
  'nativeBeginFactory':'()J','nativeFinishFactory':'(J)Z','nativeNewOwner':'()J',
  'nativeBegin':'(JJ)Z','nativeEnd':'(J)Z','nativeGate':'(JZI)Z','nativeInvalidate':'(JI)Z'}
}
def sha(data):return hashlib.sha256(data).hexdigest()
def verify_inputs(root, manifest):
 for path,expected in manifest.items():
  p=Path(path)
  if p.is_absolute() or '..' in p.parts or not (root/p).is_file() or sha((root/p).read_bytes())!=expected:
   raise ValueError('Native input drift: '+path)
def verify_badging(text):
 pkg=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'",text)
 if not pkg or pkg[1]!=PACKAGE or int(pkg[2])!=171906:raise ValueError('Wrong update package/version')
 if 'application-debuggable' in text:raise ValueError('Debug APK is not a release')
 if not re.search(r"^(?:minSdkVersion|sdkVersion):\s*'27'\s*$",text,re.M) or not re.search(r"^targetSdkVersion:\s*'35'\s*$",text,re.M):raise ValueError('Changed SDK contract')
 abis=re.search(r"^native-code: (.+)$",text,re.M)
 if not abis or abis[1].strip()!="'arm64-v8a'":raise ValueError('Wrong ABI packaging')
 return {'package':pkg[1],'version_code':int(pkg[2]),'version_name':pkg[3],'debuggable':False,'min_sdk':27,'target_sdk':35}
def verify_dexdump(text, expected):
 classes={}
 for section in re.split(r'(?=^Class #[0-9]+\s)',text,flags=re.M):
  c=re.search(r"Class descriptor\s*:\s*'([^']+)'",section)
  if not c:continue
  methods={}
  for name,desc,flags in re.findall(r"name\s*:\s*'([^']+)'\s*\n\s*type\s*:\s*'([^']+)'\s*\n\s*access\s*:\s*([^\n]+)",section):
   if 'NATIVE' in flags:methods[name]=desc
  classes[c[1]]=methods
 for c,methods in expected.items():
  for name,desc in methods.items():
   if classes.get(c,{}).get(name)!=desc:raise ValueError('Missing native DEX definition: '+c+' '+name+desc)
 return expected

def elf_text(data):
 if data[:6]!=b'\x7fELF\x02\x01' or struct.unpack_from('<H',data,18)[0]!=183:raise ValueError('Non ARM64 ELF')
 shoff=struct.unpack_from('<Q',data,40)[0];size,n,strings=struct.unpack_from('<HHH',data,58)
 if size!=64 or not 0<strings<n or shoff+n*64>len(data):raise ValueError('Invalid ELF section headers')
 hdr=lambda i:struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*64)
 h=hdr(strings);names=data[h[4]:h[4]+h[5]]
 for i in range(n):
  h=hdr(i);name=names[h[0]:].split(b'\0',1)[0]
  if name==b'.text':return data[h[4]:h[4]+h[5]]
 raise ValueError('ELF executable section absent')

def verify_payload(apk, lock, aar=None):
 result={'apk_sha256':sha(apk.read_bytes()),'bytes':apk.stat().st_size}
 with zipfile.ZipFile(apk) as z:
  names=z.namelist()
  if len(names)!=len(set(names)) or z.testzip():raise ValueError('Corrupt/duplicate APK members')
  for n in names:
   if n.startswith('lib/') and not n.startswith('lib/arm64-v8a/'):raise ValueError('Unexpected ABI')
   if n.endswith(('.wav','.f32')) and 'deepfilter' in n:raise ValueError('Test audio in APK')
  models=json.loads(lock.read_text())['models'];result['models']={}
  for m in models:
   path='assets/deepfilter/'+m['asset']
   if path not in names:raise ValueError('Missing bundled model: '+path)
   prepare_models.validate_archive(z.read(path),m);result['models'][m['id']]=m['sha256']
  if 'assets/deepfilter/notices.txt' not in names:raise ValueError('Missing runtime attribution asset')
  with tempfile.TemporaryDirectory() as td:
   expected={'libringrtc.so':{'Java_org_signal_ringrtc_CallDenoiseGate_'+s for s in GATE}|{'Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_'+s for s in BRIDGE},
    'libringrtc_rffi.so':{'Rust_MollyCallDenoise'+s for s in CPP},
    'libmolly_deepfilter.so':{'molly_df_'+s for s in ('abi_version','create','configure','process','reset','destroy')}}
   result['libraries']={}
   for name,wanted in expected.items():
    p=Path(td)/name
    try:data=z.read('lib/arm64-v8a/'+name)
    except KeyError as e:raise ValueError('Missing native library: '+name) from e
    p.write_bytes(data);defined=symbols(p)
    if not wanted<=defined:raise ValueError('Native symbols missing: '+str(wanted-defined))
    text=elf_text(data)
    if aar:
     with zipfile.ZipFile(aar) as a:
      if text!=elf_text(a.read('jni/arm64-v8a/'+name)):raise ValueError('Executable native code differs from verified AAR')
    # Verify actual program header geometry for 16 KB page compatibility.
    offset=struct.unpack_from('<Q',data,32)[0];stride,count=struct.unpack_from('<HH',data,54)
    for i in range(count):
     typ,flags,off,va,pa,fs,ms,align=struct.unpack_from('<IIQQQQQQ',data,offset+stride*i)
     if typ==1 and (align<16384 or (va-off)%16384):raise ValueError('Unaligned ELF '+name)
    result['libraries'][name]={'sha256':sha(data),'text_sha256':sha(text),'verified_exports':sorted(wanted)}
 return result

def decode_dexdump(data: bytes) -> str:
 # DEX literals are modified UTF-8 and can include surrogate encodings that
 # are invalid standard UTF-8. Preserve those bytes as escapes, not omissions.
 # Class/method/descriptor evidence for this ABI is strictly ASCII.
 return data.decode('utf-8',errors='backslashreplace')

def verify_sdk(apk,aapt2,dexdump):
 badging=subprocess.check_output([str(aapt2),'dump','badging',str(apk)],text=True)
 result=verify_badging(badging)
 resources=subprocess.check_output([str(aapt2),'dump','resources',str(apk)],text=True)
 if not re.search(r'^\s*resource 0x[0-9a-fA-F]{8} (?:[a-zA-Z0-9_.]+:)?raw/deepfilter_notices(?:\s|$)',resources,re.M):raise ValueError('Attribution resource removed by shrinking')
 dumps=[]
 with zipfile.ZipFile(apk) as z,tempfile.TemporaryDirectory() as td:
  for name in z.namelist():
   if re.fullmatch('classes[0-9]*.dex',name):
    p=Path(td)/name;p.write_bytes(z.read(name));dumps.append(decode_dexdump(subprocess.check_output([str(dexdump),str(p)])))
 result['jni']=verify_dexdump('\n'.join(dumps),JNI)
 return result

def signature(apk,jar):
 text=subprocess.check_output(['java','-jar',str(jar),'verify','--verbose','--print-certs',str(apk)],text=True)
 cert=re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',text)
 if not cert or cert[1]!=CERT:raise ValueError('Signing identity changed')
 if not re.search(r'v2 scheme.*: true',text) or not re.search(r'v3 scheme.*: true',text):raise ValueError('Missing APK v2/v3 signature')
 return cert[1]
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--aar',type=Path,required=True)
 p.add_argument('--aapt2',type=Path,required=True);p.add_argument('--dexdump',type=Path,required=True);p.add_argument('--baseline',type=Path);p.add_argument('--apksigner',type=Path)
 a=p.parse_args();r=verify_payload(a.apk,Path(__file__).with_name('models.lock.json'),a.aar);r.update(verify_sdk(a.apk,a.aapt2,a.dexdump))
 if a.baseline:
  if not a.apksigner:p.error('--baseline requires --apksigner')
  r['certificate_sha256']=signature(a.apk,a.apksigner)
  if signature(a.baseline,a.apksigner)!=r['certificate_sha256']:raise ValueError('Different baseline key')
  old=subprocess.check_output([str(a.aapt2),'dump','badging',str(a.baseline)],text=True)
  if not re.search(r"package: name='"+re.escape(PACKAGE)+r"' versionCode='171905'",old):raise ValueError('Unexpected installed baseline')
  r['in_place_update_identity']=True
 print(json.dumps(r,indent=2))
