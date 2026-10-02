#!/usr/bin/env python3
from pathlib import Path
import importlib.util
import io
import tempfile
import unittest
import zipfile

class NativePackageTests(unittest.TestCase):
    def test_stock_or_empty_aar_is_not_deepfilternet(self):
        path=Path(__file__).with_name('package_native.py')
        self.assertTrue(path.exists(),'DeepFilterNet native AAR validation is not implemented')
        spec=importlib.util.spec_from_file_location('package_native',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'stock.aar'
            classes=io.BytesIO()
            with zipfile.ZipFile(classes,'w') as archive:archive.writestr('org/signal/ringrtc/CallManager.class',b'fixture')
            with zipfile.ZipFile(target,'w') as archive:archive.writestr('classes.jar',classes.getvalue())
            with self.assertRaisesRegex(ValueError,'CallDenoiseGate'):module.verify(target,False)
if __name__=='__main__':unittest.main()
