import importlib.util
from pathlib import Path
import urllib.request
import unittest
spec=importlib.util.spec_from_file_location('lock',Path(__file__).with_name('restore_runtime_lock.py'))
lock=importlib.util.module_from_spec(spec);spec.loader.exec_module(lock)
class RedirectTest(unittest.TestCase):
    def test_cross_origin_redirect_strips_authorization(self):
        self.assertTrue(hasattr(lock,'SafeRedirect'),'Redirect handler must strip API authentication at the storage boundary')
        handler=lock.SafeRedirect()
        req=urllib.request.Request('https://api.github.com/example',headers={'Authorization':'Bearer test','Accept':'application/vnd.github+json'})
        redirected=handler.redirect_request(req,None,302,'Found',{},'https://storage.example/blob?sig=example')
        self.assertIsNone(redirected.get_header('Authorization'))
        self.assertEqual(req.get_header('Authorization'),'Bearer test')
        same=handler.redirect_request(req,None,302,'Found',{},'https://api.github.com/other')
        self.assertEqual(same.get_header('Authorization'),'Bearer test')
if __name__=='__main__': unittest.main()
