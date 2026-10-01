#!/usr/bin/env python3
"""CI-only public fixtures and test jars; none are packaged in the APK."""
import argparse
import hashlib
import json
from pathlib import Path
from prepare_models import download, git_blob, HERE

def prepare(output: Path) -> None:
    output.mkdir(parents=True,exist_ok=True)
    lock=json.loads((HERE/'fixtures.lock.json').read_text())
    records=[]
    for fixture in lock['fixtures']:
        data=download(lock['source_repository'],lock['source_revision'],fixture['source_path'],8*1024*1024)
        if git_blob(data)!=fixture['git_blob_sha1']: raise ValueError('Fixture Git hash mismatch')
        (output/Path(fixture['source_path']).name).write_bytes(data)
        records.append(fixture|{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
    (output/'fixture-provenance.json').write_text(json.dumps(lock|{'fixtures':records},indent=2)+'\n')
    # These hashes already occur in this repository's Gradle verification metadata.
    import urllib.request
    for path,expected in [
      ('junit/junit/4.13.2/junit-4.13.2.jar','8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'),
      ('org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar','66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9')]:
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+path,timeout=60) as r: data=r.read(2*1024*1024)
        if hashlib.sha256(data).hexdigest()!=expected: raise ValueError('JUnit dependency hash mismatch')
        (output/Path(path).name).write_bytes(data)

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--output',type=Path,required=True)
    prepare(p.parse_args().output)
