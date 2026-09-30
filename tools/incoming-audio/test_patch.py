"""Fixture-based patch checks, not a substitute for an actual WebRTC build."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('patcher', Path(__file__).with_name('patch_ringrtc.py'))
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)
SOURCES = Path(__file__).resolve().parents[2] / 'native/incoming-audio'

class PatchTests(unittest.TestCase):
    def fixture(self, root):
        files = {
            'config/version.properties': 'webrtc.version=7778d\nringrtc.version.major=2\nringrtc.version.minor=69\nringrtc.version.revision=5\n',
            'src/webrtc/src/audio/audio_transport_impl.h': '#include "api/audio/audio_mixer.h"\n  AudioFrame mixed_frame_;\n',
            'src/webrtc/src/audio/audio_transport_impl.cc': 'CAPTURE_UNCHANGED\n  mixer_->Mix(nChannels, &mixed_frame_);\nProcessReverseAudioFrame(audio_processing_, &mixed_frame_);\n  mixer_->Mix(number_of_channels, &mixed_frame_);\n',
            'src/webrtc/src/audio/BUILD.gn': '"audio_transport_impl.h",\n',
            'src/rust/src/lib.rs': '        mod jni_call_manager;\n',
            'src/android/api/org/signal/ringrtc/CallManager.java': '      CallManager.isInitialized = true;\n'
        }
        for name, text in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        (root / 'src/rust/src/android/api').mkdir(parents=True, exist_ok=True)

    def test_hook_precedes_echo_reference_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            patcher.patch(root, SOURCES)
            before = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            patcher.patch(root, SOURCES)
            after = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            self.assertEqual(before, after)
            source = (root / 'src/webrtc/src/audio/audio_transport_impl.cc').read_text()
            self.assertTrue(source.startswith('CAPTURE_UNCHANGED'))
            self.assertEqual(source.count('incoming_audio_.Process(&mixed_frame_);'), 2)
            self.assertLess(source.index('incoming_audio_.Process'), source.index('ProcessReverseAudioFrame'))

    def test_unexpected_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / 'config/version.properties').write_text('webrtc.version=unexpected\n')
            with self.assertRaises(RuntimeError):
                patcher.patch(root, SOURCES)

    def test_unexpected_anchor_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / 'src/webrtc/src/audio/audio_transport_impl.h').write_text('changed upstream\n')
            with self.assertRaises(RuntimeError):
                patcher.patch(root, SOURCES)

if __name__ == '__main__':
    unittest.main()
