#!/usr/bin/env python3
"""Verify both call-hook ABI sides, then add the checked Android model runtime."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import tempfile
import zipfile
import verify_runtime

RUNTIME_SHA='ad1c51cc08d857378f78c42a4c7d265e855a52a919a9d0ff283efb4a794a2eb1'
GATE=('nativeBeginFactory','nativeFinishFactory','nativeNewOwner','nativeBegin','nativeEnd','nativeGate','nativeInvalidate')
BRIDGE=('nativeVersion','nativePaths','nativeApply','nativeBypass','nativeRetry','nativeStatus')
CPP=('Version','BeginFactory','FinishFactory','NewOwner','Begin','End','Gate','Invalidate','Paths','Configure','Bypass','Retry','Status')

def symbols(path):
    text=subprocess.check_output(['readelf','--dyn-syms','--wide',str(path)],text=True)
    return {line.split()[-1] for line in text.splitlines() if len(line.split())>=8 and line.split()[6]!='UND'}

def verify(path:Path,require_runtime=True):
    result={'aar_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'native_call_abi':1}
    with zipfile.ZipFile(path) as aar,tempfile.TemporaryDirectory() as tmp:
        if aar.testzip():raise ValueError('Corrupt AAR')
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            if 'org/signal/ringrtc/CallDenoiseGate.class' not in jar.namelist():raise ValueError('Missing CallDenoiseGate; stock RingRTC is not accepted')
        expected={
            'libringrtc.so':{'Java_org_signal_ringrtc_CallDenoiseGate_'+name for name in GATE}|{'Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_'+name for name in BRIDGE},
            'libringrtc_rffi.so':{'Rust_MollyCallDenoise'+name for name in CPP}
        }
        for name,names in expected.items():
            file=Path(tmp)/name;data=aar.read('jni/arm64-v8a/'+name);file.write_bytes(data)
            if data[:6]!=b'\x7fELF\x02\x01' or int.from_bytes(data[18:20],'little')!=183:raise ValueError('Non-ARM64 call library')
            missing=names-symbols(file)
            if missing:raise ValueError(f'Missing defined call exports: {sorted(missing)}')
            result[name]={'sha256':hashlib.sha256(data).hexdigest(),'exports':sorted(names)}
        if require_runtime:
            file=Path(tmp)/'libmolly_deepfilter.so';file.write_bytes(aar.read('jni/arm64-v8a/libmolly_deepfilter.so'))
            result['runtime']=verify_runtime.verify(file)
            if result['runtime']['sha256']!=RUNTIME_SHA:raise ValueError('Unexpected runtime bytes; revalidate the new runtime separately')
    return result

def package(aar:Path,runtime:Path,output:Path):
    verify(aar,False)
    if hashlib.sha256(runtime.read_bytes()).hexdigest()!=RUNTIME_SHA:raise ValueError('Wrong Android runtime')
    verify_runtime.verify(runtime)
    temporary=output.with_suffix('.tmp.aar');output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(aar) as source,zipfile.ZipFile(temporary,'w') as dest:
        for info in source.infolist():
            if info.filename!='jni/arm64-v8a/libmolly_deepfilter.so':dest.writestr(info,source.read(info.filename))
        dest.writestr('jni/arm64-v8a/libmolly_deepfilter.so',runtime.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    evidence=verify(temporary);temporary.replace(output);return evidence
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('aar',type=Path);parser.add_argument('--runtime',type=Path);parser.add_argument('--output',type=Path);args=parser.parse_args()
    result=package(args.aar,args.runtime,args.output) if args.runtime and args.output else verify(args.aar)
    print(json.dumps(result,indent=2))
