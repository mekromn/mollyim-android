#!/usr/bin/env python3
"""Archive validation, not inference. Tests run without network."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

HERE = Path(__file__).parent

class ProvenanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('prepare_models', HERE / 'prepare_models.py')
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def fixture(self, **changes):
        config = '[train]\nmodel=deepfilternet3\n[df]\nsr=48000\nhop_size=480\nfft_size=960\nnb_erb=32\nnb_df=96\ndf_order=5\ndf_lookahead=2\n[deepfilternet]\nconv_lookahead=2\n'
        members = {'tmp/export/config.ini': config.encode(), 'tmp/export/enc.onnx': b'enc', 'tmp/export/erb_dec.onnx': b'erb', 'tmp/export/df_dec.onnx': b'df'}
        members.update(changes)
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode='w:gz') as tar:
            for name, data in members.items():
                member = tarfile.TarInfo(name)
                if data is None:
                    member.type = tarfile.SYMTYPE; member.linkname = '/tmp/outside'
                    tar.addfile(member)
                else:
                    member.size = len(data); tar.addfile(member, io.BytesIO(data))
        data = out.getvalue()
        lock = {'id': 'standard', 'asset': 'DeepFilterNet3_onnx.tar.gz', 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'git_blob_sha1': hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest(), 'meta': {'sample_rate':48000,'hop':480,'fft':960,'lookahead':2,'df_order':5,'erb_bands':32,'df_bins':96,'intrinsic_delay':1440}, 'members': []}
        for name, raw in members.items():
            if raw is not None: lock['members'].append({'path':name,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
        return data, lock

    def test_reject_modified_archive(self):
        data, lock = self.fixture()
        self.module.validate_archive(data, lock)
        with self.assertRaises(ValueError): self.module.validate_archive(data[:-1]+bytes([data[-1]^1]),lock)

    def test_reject_truncated_or_unsafe_members(self):
        data, lock = self.fixture()
        with self.assertRaises(ValueError): self.module.validate_archive(data[:50],lock)
        for path, content in [('../escape',b'x'),('/absolute',b'x'),('link',None),('a/../../escape',b'x')]:
            with self.subTest(path=path):
                bad, badlock = self.fixture(**{path:content})
                with self.assertRaises(ValueError): self.module.validate_archive(bad,badlock)

    def test_require_pinned_metadata(self):
        data, lock = self.fixture()
        lock['meta']['hop']=240
        with self.assertRaises(ValueError): self.module.validate_archive(data,lock)
        manifest=json.loads((HERE/'models.lock.json').read_text())
        self.assertEqual(manifest['source_revision'],'d375b2d8309e0935d165700c91da9de862a99c31')
        self.assertEqual([m['sha256'] for m in manifest['models']], ['c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616','5998e58e8ba0e09bb76986ef97b84afa065a571ef282d4a1222f341e3251cf3a'])
        self.assertEqual([m['meta']['intrinsic_delay'] for m in manifest['models']],[1440,480])

    def test_atomic_asset_publish(self):
        data, lock=self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            dst=Path(tmp)/lock['asset']
            self.module.publish_asset(data,lock,dst)
            self.assertEqual(dst.read_bytes(),data)
            with self.assertRaises(ValueError): self.module.publish_asset(data[:15],lock,dst)
            self.assertEqual(dst.read_bytes(),data)
            self.assertEqual([p.name for p in Path(tmp).iterdir()],[lock['asset']])

if __name__=='__main__': unittest.main()
