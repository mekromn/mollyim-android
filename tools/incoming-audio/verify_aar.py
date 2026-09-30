#!/usr/bin/env python3
"""Reject an AAR without both sides of the native receive-audio ABI."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile

def verify(path: Path) -> dict:
    reader = shutil.which('llvm-readelf') or shutil.which('readelf')
    if not reader:
        raise RuntimeError('readelf is required to verify native exports')
    java_prefix = 'Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_'
    requirements = {
        'jni/arm64-v8a/libringrtc.so': [java_prefix + s for s in ('nativeVersion', 'nativeApply', 'nativeReset', 'nativeMeter', 'nativeFrames')],
        'jni/arm64-v8a/libringrtc_rffi.so': ['Rust_MollyIncomingAudio' + s for s in ('Version', 'Configure', 'Reset', 'Meter', 'Frames')]
    }
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory() as tmp:
        if archive.testzip() is not None:
            raise RuntimeError('Corrupt AAR')
        if 'classes.jar' not in archive.namelist():
            raise RuntimeError('AAR has no Java classes')
        for entry, symbols in requirements.items():
            data = archive.read(entry)
            if len(data) < 100_000 or not data.startswith(b'\x7fELF'):
                raise RuntimeError(f'Invalid native library: {entry}')
            local = Path(tmp) / Path(entry).name
            local.write_bytes(data)
            output = subprocess.check_output([reader, '--dyn-syms', '--wide', str(local)], text=True)
            for symbol in symbols:
                if not any(symbol in line.split() and 'UND' not in line.split() for line in output.splitlines()):
                    raise RuntimeError(f'Missing defined export {symbol} in {entry}')
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'native_abi': 1, 'architecture': 'arm64-v8a'}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('aar', type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.aar.resolve()), indent=2))
