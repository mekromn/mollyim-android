#!/usr/bin/env python3
"""Exact pinned integration anchors. Not a native Android compilation test."""
from pathlib import Path
import importlib.util
import os
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
class PatchTest(unittest.TestCase):
    def test_pinned_receive_send_lifecycle_and_idempotence(self):
        path = ROOT / 'tools/deepfilter/patch_ringrtc.py'
        self.assertTrue(path.is_file(), 'DeepFilterNet call hook patcher is not implemented')
        spec = importlib.util.spec_from_file_location('call_patch', path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        ring = Path(os.environ['RINGRTC_SOURCE']); web = Path(os.environ['WEBRTC_SOURCE'])
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'ringrtc'
            shutil.copytree(ring, target)
            shutil.copytree(web, target / 'src/webrtc/src', dirs_exist_ok=True)
            module.patch(target, ROOT)
            files = lambda: {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()}
            before = files(); module.patch(target, ROOT); self.assertEqual(before, files())
            source = (target / 'src/webrtc/src/audio/audio_transport_impl.cc').read_text()
            capture = source.index('denoiser_.StampCapture(audio_frame.get())')
            self.assertLess(capture, source.index('  ProcessCaptureFrame(', capture))
            send = source.index('void AudioTransportImpl::SendProcessedData')
            self.assertLess(source.index('denoiser_.ProcessSent', send), source.index('MutexLock lock(&capture_lock_)', send))
            mix = source.index('mixer_->Mix(nChannels')
            self.assertLess(source.index('incoming_audio_.Process', mix), source.index('ProcessReverseAudioFrame', mix))
            self.assertIn('denoiser_.Receiver()', source)
            frame = (target / 'src/webrtc/src/api/audio/audio_frame.cc').read_text()
            for field in ('molly_owner_', 'molly_gate_generation_', 'molly_stream_generation_', 'molly_source_start_'):
                self.assertIn(f'{field} = src.{field};', frame)
                self.assertIn(f'{field} = 0;', frame)
            group = (target / 'src/android/api/org/signal/ringrtc/GroupCall.java').read_text()
            self.assertIn('CallDenoiseGate.mute(denoiseOwner, true, true)', group)
            self.assertIn('CallDenoiseGate.end(denoiseOwner)', group)
            manager = (target / 'src/android/api/org/signal/ringrtc/CallManager.java').read_text()
            self.assertIn('CallDenoiseGate.createFactory', manager)
            self.assertIn('CallDenoiseGate.mute(callContext.denoiseOwner, !enable, false)', manager)
            state = (target / 'src/webrtc/src/audio/audio_state.cc').read_text()
            self.assertIn('audio_transport_.MollyCaptureStopped();', state)
            self.assertIn('mod call_denoise;', (target / 'src/rust/src/lib.rs').read_text())
    def test_required_source_drift_is_rejected(self):
        path = ROOT / 'tools/deepfilter/patch_ringrtc.py'
        self.assertTrue(path.is_file(), 'DeepFilterNet call hook patcher is not implemented')
        spec = importlib.util.spec_from_file_location('call_patch', path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp);(target/'config').mkdir();(target/'config/version.properties').write_text('webrtc.version=WRONG\n')
            with self.assertRaises(RuntimeError):module.patch(target,ROOT)
if __name__ == '__main__': unittest.main()
