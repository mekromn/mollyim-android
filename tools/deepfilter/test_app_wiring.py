#!/usr/bin/env python3
"""Source/Java ABI checks; actual Android compilation is a separate gate."""
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'app/src/main/java/org/thoughtcrime/securesms/webrtc/audio'
class WiringTests(unittest.TestCase):
    def test_native_bridge_is_optional_and_retained(self):
        path=A/'CallDenoiseBridge.java'
        self.assertTrue(path.exists(),'App denoiser bridge not implemented')
        text=path.read_text();self.assertIn('@Keep',text)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'androidx/annotation').mkdir(parents=True)
            (p/'androidx/annotation/Keep.java').write_text('package androidx.annotation; public @interface Keep {}')
            (p/'Check.java').write_text('''import org.thoughtcrime.securesms.webrtc.audio.CallDenoiseBridge; public class Check { public static void main(String[] a) { if(CallDenoiseBridge.available())throw new AssertionError(); if(CallDenoiseBridge.paths("/x","/y","/z"))throw new AssertionError(); if(CallDenoiseBridge.status(0,new float[16]))throw new AssertionError(); if(CallDenoiseBridge.apply(0,true,0,30,false,.02f,-10,30,20))throw new AssertionError(); CallDenoiseBridge.bypass(0,true); CallDenoiseBridge.retry(0); CallDenoiseBridge.routeChanged(); System.out.println("Optional bridge with missing JNI PASS"); }}''')
            subprocess.run(['javac','-d',str(p),str(p/'androidx/annotation/Keep.java'),str(path),str(p/'Check.java')],check=True)
            subprocess.run(['java','-cp',str(p),'Check'],check=True)
            signatures=subprocess.check_output(['javap','-p','-s','-classpath',str(p),'org.thoughtcrime.securesms.webrtc.audio.CallDenoiseBridge'],text=True)
            for descriptor in ('(IZIFZFFFF)Z','(I[F)Z','(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z'):
                self.assertIn(descriptor,signatures)
    def test_background_route_and_preferences_are_wired_without_registration(self):
        controller=A/'CallDenoiseController.kt';self.assertTrue(controller.exists(),'Persistent controller not implemented')
        text=controller.read_text();self.assertIn('call_denoise_v1',text);self.assertIn('DenoiseCoordinator',text)
        self.assertIn('Dispatchers.IO',text);self.assertNotIn('AudioRecord',text);self.assertNotIn('registerAccount',text)
        self.assertIn('CallDenoiseController.initialize(context)',(A/'IncomingAudioController.kt').read_text())
        manager=(ROOT/'app/src/main/java/org/thoughtcrime/securesms/service/webrtc/SignalCallManager.java').read_text()
        start=manager.index('public void onAudioDeviceChanged(');end=manager.index('\n  }',start)
        self.assertIn('CallDenoiseBridge.routeChanged()',manager[start:end])
if __name__=='__main__':unittest.main()
