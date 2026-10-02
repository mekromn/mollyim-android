#!/usr/bin/env python3
"""Reject stock/old RingRTC and preserve existing call/DeepFilterNet verification."""
from pathlib import Path
import argparse,io,json,sys,tempfile,zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deepfilter'))
from package_native import verify,symbols
CPP=('Version','Prepare','Finish','Revoke','Preempt','Release','Command','Configure','Load','Status','Drain')
JNI=('nativeVersion','nativePrepare','nativeFinish','nativeRevoke','nativePreempt','nativeRelease','nativeCommand','nativeConfigure','nativeLoad','nativeStatus','nativeDrain')
def check(aar):
 result=verify(aar)
 with zipfile.ZipFile(aar) as z,tempfile.TemporaryDirectory() as td:
  with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as j:
   if 'org/signal/ringrtc/MockCallSession.class' not in j.namelist():raise ValueError('Missing local call session class')
  for name,prefix,names in [('libringrtc_rffi.so','Rust_MollyMock',CPP),('libringrtc.so','Java_org_signal_ringrtc_MockCallSession_',JNI)]:
   p=Path(td)/name;p.write_bytes(z.read('jni/arm64-v8a/'+name));wanted={prefix+n for n in names};missing=wanted-symbols(p)
   if missing:raise ValueError('Missing native mock call definitions: '+str(missing))
   result[name]['mock_exports']=sorted(wanted)
 result['mock_call_abi']=1;return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('aar',type=Path);a=p.parse_args();print(json.dumps(check(a.aar),indent=2))
