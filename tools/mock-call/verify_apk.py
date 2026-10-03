#!/usr/bin/env python3
"""Verify the actual optimized Mock Call Lab APK, never just artifact filenames."""
from pathlib import Path
import argparse,json,re,subprocess,sys,tempfile,zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deepfilter'))
import verify_release as df
from verify_native import CPP,JNI
from package_native import symbols

MOCK_JNI={'Lorg/signal/ringrtc/MockCallSession;':{
 'nativeVersion':'()I','nativePrepare':'()J','nativeFinish':'(J)Z','nativeRevoke':'(J)V',
 'nativePreempt':'()Z','nativeRelease':'(J)Z','nativeCommand':'(JIJJJJ)J',
 'nativeConfigure':'(J[F)J','nativeLoad':'(JIII[S)J','nativeStatus':'(J[F)Z','nativeDrain':'(JI[J[F)I'}}

def badging(text):
 match=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'",text)
 if not match or match[1]!=df.PACKAGE or int(match[2])!=171907:raise ValueError('Wrong mock update identity')
 if 'application-debuggable' in text:raise ValueError('A debug APK is not the deliverable')
 for label,value in [('(?:minSdkVersion|sdkVersion)',27),('targetSdkVersion',35)]:
  if not re.search(r'^'+label+r":\s*'"+str(value)+r"'\s*$",text,re.M):raise ValueError('SDK contract changed')
 abi=re.search(r'^native-code: (.+)$',text,re.M)
 if not abi or abi[1].strip()!="'arm64-v8a'":raise ValueError('Unexpected packaged architecture')
 return {'package':match[1],'version_code':int(match[2]),'version_name':match[3],'debuggable':False,'min_sdk':27,'target_sdk':35}

def manifest(text):
 target='org.thoughtcrime.securesms.webrtc.audio.mock.MockCallLabActivity'
 lines=text.splitlines();found=[]
 for i,line in enumerate(lines):
  if re.search(r'\bE: activity(?:\s|$)',line):
   indent=len(line)-len(line.lstrip());end=i+1
   while end<len(lines) and len(lines[end])-len(lines[end].lstrip())>indent:end+=1
   block='\n'.join(lines[i:end])
   if target in block:found.append(block)
 if len(found)!=1 or not re.search(r'android:exported[^\n]*\(type 0x12\)0x0\s*$',found[0],re.M):raise ValueError('Mock activity absent or exported')
 return {'mock_activity':target,'exported':False}

def verify_mock_dex(text):return df.verify_dexdump(text,MOCK_JNI)

def verify(apk,aar,sdk):
 root=Path(__file__).resolve().parents[2]
 result=df.verify_payload(apk,root/'tools/deepfilter/models.lock.json',aar)
 result.update(badging(subprocess.check_output([str(sdk/'aapt2'),'dump','badging',str(apk)],text=True)))
 result.update(manifest(subprocess.check_output([str(sdk/'aapt2'),'dump','xmltree','--file','AndroidManifest.xml',str(apk)],text=True)))
 resources=subprocess.check_output([str(sdk/'aapt2'),'dump','resources',str(apk)],text=True)
 if not re.search(r'\braw/deepfilter_notices\b',resources):raise ValueError('Attribution was removed')
 with zipfile.ZipFile(apk) as z,tempfile.TemporaryDirectory() as td:
  dumps=[]
  for name in z.namelist():
   if re.fullmatch('classes[0-9]*.dex',name):
    p=Path(td)/name;p.write_bytes(z.read(name));dumps.append(df.decode_dexdump(subprocess.check_output([str(sdk/'dexdump'),str(p)])))
  text='\n'.join(dumps)
  result['deepfilter_jni']=df.verify_dexdump(text,df.JNI)
  result['mock_jni']=verify_mock_dex(text)
  for name,prefix,expected in [('libringrtc_rffi.so','Rust_MollyMock',CPP),('libringrtc.so','Java_org_signal_ringrtc_MockCallSession_',JNI)]:
   p=Path(td)/name;p.write_bytes(z.read('lib/arm64-v8a/'+name));wanted={prefix+n for n in expected}
   if not wanted<=symbols(p):raise ValueError('Mock native export absent: '+name)
   result['libraries'][name]['mock_exports']=sorted(wanted)
 result['physical_phone_testing']='NOT RUN'
 return result

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--aar',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--baseline',type=Path);p.add_argument('--apksigner',type=Path)
 a=p.parse_args();r=verify(a.apk,a.aar,a.sdk)
 if a.baseline:
  if not a.apksigner:p.error('--baseline requires --apksigner')
  if df.signature(a.apk,a.apksigner)!=df.signature(a.baseline,a.apksigner):raise ValueError('Certificate mismatch')
  old=subprocess.check_output([str(a.sdk/'aapt2'),'dump','badging',str(a.baseline)],text=True)
  if not re.search(r"package: name='"+re.escape(df.PACKAGE)+r"' versionCode='171906'",old):raise ValueError('Wrong delivered baseline')
  r['certificate_sha256']=df.CERT;r['in_place_update_identity']=True
 print(json.dumps(r,indent=2))
