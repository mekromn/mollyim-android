#!/usr/bin/env python3
"""Bootstrap the already resolved, audited lock; never resolve a new dependency set."""
import hashlib,io,os,urllib.request,zipfile
from pathlib import Path
from urllib.parse import urlsplit

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected=super().redirect_request(req,fp,code,msg,headers,newurl)
        if redirected is not None and urlsplit(req.full_url).netloc != urlsplit(newurl).netloc:
            redirected.remove_header('Authorization')
        return redirected

TARGET=Path('native/deepfilter-runtime/Cargo.lock')
EXPECTED='2bcb41dc28a87a25ddff9fb62a03aedb1ee951ad51617d899682b0bee3408271'
if not TARGET.exists():
    request=urllib.request.Request('https://api.github.com/repos/mekromn/mollyim-android/actions/artifacts/11194046216/zip',headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json'})
    with urllib.request.build_opener(SafeRedirect()).open(request,timeout=120) as response: data=response.read(1024*1024)
    if hashlib.sha256(data).hexdigest()!='32a15740bfd75154885890f4d9c887d845a3eef03af77cb30cbdf47bde19dccc': raise ValueError('Lock artifact hash mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive: payload=archive.read('Cargo.lock')
    if hashlib.sha256(payload).hexdigest()!=EXPECTED: raise ValueError('Lock file hash mismatch')
    TARGET.write_bytes(payload)
if hashlib.sha256(TARGET.read_bytes()).hexdigest()!=EXPECTED: raise ValueError('Unexpected dependency lock')
print('Exact audited Cargo lock verified')
