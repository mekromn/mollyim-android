#!/usr/bin/env python3
"""Build-time verified assets. No archive extraction, no runtime network access."""
import argparse
import configparser
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent

def git_blob(data: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

def validate_archive(data: bytes, expected: dict) -> dict:
    if len(data) != expected['bytes'] or hashlib.sha256(data).hexdigest() != expected['sha256'] or git_blob(data) != expected['git_blob_sha1']:
        raise ValueError('Model archive length or cryptographic hash mismatch')
    required = {m['path']: m for m in expected['members']}
    seen = set()
    config = None
    total = 0
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            for member in archive:
                path = PurePosixPath(member.name)
                if not member.isfile() or path.is_absolute() or '..' in path.parts or '\\' in member.name or ':' in member.name:
                    raise ValueError('Unsafe model archive member')
                record = required.get(member.name)
                total += member.size
                if record is None or member.name in seen or member.size != record['bytes'] or total > 64*1024*1024:
                    raise ValueError('Unexpected or oversized model archive member')
                file = archive.extractfile(member)
                if file is None: raise ValueError('Unreadable model member')
                payload = file.read(member.size + 1)
                if len(payload) != member.size or hashlib.sha256(payload).hexdigest() != record['sha256']:
                    raise ValueError('Model member hash mismatch')
                seen.add(member.name)
                if path.name == 'config.ini':
                    config = configparser.ConfigParser(interpolation=None)
                    config.read_string(payload.decode('utf-8'))
    except (tarfile.TarError, OSError, EOFError, UnicodeError, configparser.Error) as error:
        raise ValueError('Malformed model archive') from error
    if seen != set(required) or config is None: raise ValueError('Incomplete model archive')
    try:
        df = config['df']; model = config['deepfilternet']
        if config['train']['model'] != 'deepfilternet3': raise ValueError('Unsupported model')
        look = max(int(model['conv_lookahead']), int(df.get('df_lookahead', model.get('df_lookahead','-1'))))
        meta = {'sample_rate':int(df['sr']), 'hop':int(df['hop_size']), 'fft':int(df['fft_size']), 'lookahead':look,
                'df_order':int(df.get('df_order',model.get('df_order','-1'))), 'erb_bands':int(df['nb_erb']), 'df_bins':int(df['nb_df'])}
        meta['intrinsic_delay'] = meta['fft']-meta['hop']+look*meta['hop']
    except (KeyError, ValueError) as error:
        raise ValueError('Missing model metadata') from error
    if meta != expected['meta']: raise ValueError('Pinned model metadata mismatch')
    return meta

def publish_asset(data: bytes, expected: dict, destination: Path) -> None:
    validate_archive(data, expected)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.verified-',dir=destination.parent,delete=False) as output:
            temp = Path(output.name)
            output.write(data); output.flush(); os.fsync(output.fileno())
        os.replace(temp, destination)
    finally:
        if temp is not None: temp.unlink(missing_ok=True)

def download(repo: str, revision: str, source: str, max_bytes: int) -> bytes:
    url = f'https://raw.githubusercontent.com/{repo}/{revision}/{source}'
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read(max_bytes + 1)
    if len(data) > max_bytes: raise ValueError('Download exceeds pinned size')
    return data

def prepare(output: Path, cache: Path | None = None) -> None:
    lock = json.loads((HERE/'models.lock.json').read_text())
    for model in lock['models']:
        cached = cache/model['asset'] if cache is not None else None
        data = cached.read_bytes() if cached is not None and cached.is_file() else download(lock['source_repository'],lock['source_revision'],model['source_path'],model['bytes'])
        publish_asset(data,model,output/model['asset'])
        print(f"Verified {model['id']}: {model['sha256']}")
    manifest = json.dumps(lock,indent=2).encode()
    temporary = output/'.manifest.tmp'
    try:
        temporary.write_bytes(manifest); os.replace(temporary,output/'models.json')
    finally:
        temporary.unlink(missing_ok=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--cache',type=Path)
    args=parser.parse_args()
    prepare(args.output,args.cache)
