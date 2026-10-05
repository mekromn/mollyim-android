#!/usr/bin/env python3
"""Regression: a pinned extra mobile model must survive Android .gz expansion."""
import gzip,hashlib,io,json,tarfile,tempfile,zipfile
from pathlib import Path
import finalize_models

def git_blob(data: bytes) -> str:
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

def model_fixture(root: Path):
    payloads={
        'tmp/export/enc.onnx':b'enc',
        'tmp/export/erb_dec.onnx':b'erb',
        'tmp/export/df_dec.onnx':b'df',
        'tmp/export/config.ini':b'''[train]\nmodel=deepfilternet3\n[deepfilternet]\nconv_lookahead=0\ndf_order=5\n[df]\nsr=48000\nhop_size=480\nfft_size=960\nnb_erb=32\nnb_df=96\ndf_order=5\ndf_lookahead=0\n'''
    }
    raw=io.BytesIO()
    with tarfile.open(fileobj=raw,mode='w:gz') as t:
        for d in ('tmp','tmp/export'):
            info=tarfile.TarInfo(d);info.type=tarfile.DIRTYPE;info.mode=0o755;t.addfile(info)
        for name,data in payloads.items():
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644
            t.addfile(info,io.BytesIO(data))
    data=raw.getvalue()
    asset='DeepFilterNet3_onnx_mobile.tar.gz'
    models=root/'mobile';models.mkdir()
    (models/asset).write_bytes(data)
    lock={
        'schema':1,'id':'mobile_fused','wire_id':2,'asset':asset,
        'source_repository':'fixture','source_revision':'1','source_path':asset,
        'bytes':len(data),'git_blob_sha1':git_blob(data),'sha256':hashlib.sha256(data).hexdigest(),
        'members':[{'path':n,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()} for n,b in payloads.items()],
        'meta':{'sample_rate':48000,'hop':480,'fft':960,'lookahead':0,'df_order':5,'erb_bands':32,'df_bins':96,'intrinsic_delay':480}
    }
    lock_path=root/'mobile.lock.json';lock_path.write_text(json.dumps(lock))
    return models,lock_path,lock,data

def main():
    with tempfile.TemporaryDirectory() as td:
        root=Path(td)
        primary=root/'primary';primary.mkdir()
        primary_lock=root/'primary.lock.json';primary_lock.write_text(json.dumps({'schema':1,'models':[]}))
        mobile_dir,mobile_lock,mobile,data=model_fixture(root)
        src=root/'input.apk';out=root/'output.apk'
        with zipfile.ZipFile(src,'w') as z:
            z.writestr('classes.dex',b'compiled bytes unchanged')
            z.writestr('assets/deepfilter/'+mobile['asset'][:-3],gzip.decompress(data))
        report=finalize_models.finalize(
            src,out,primary,primary_lock,
            extra_models=mobile_dir,extra_lock=mobile_lock
        )
        with zipfile.ZipFile(out) as z:
            assert z.read('classes.dex')==b'compiled bytes unchanged'
            assert z.read('assets/deepfilter/'+mobile['asset'])==data
            assert 'assets/deepfilter/'+mobile['asset'][:-3] not in z.namelist()
        assert 'assets/deepfilter/'+mobile['asset'] in report['added_models']
        assert 'assets/deepfilter/'+mobile['asset'][:-3] in report['removed_expanded_aliases']
        print('PASS mobile fused .tar alias canonicalized to verified .tar.gz without changing compiled bytes')

if __name__=='__main__':
    main()
