#!/usr/bin/env python3
"""Patch the pinned RingRTC/WebRTC checkout. Fail rather than guess on drift."""
from pathlib import Path
import argparse
import shutil

def replace_once(path: Path, before: str, after: str) -> None:
    text = path.read_text()
    if after in text:
        return
    if text.count(before) != 1:
        raise RuntimeError(f"Expected one patch anchor in {path}: {before!r}")
    path.write_text(text.replace(before, after, 1))

def patch(ringrtc: Path, sources: Path) -> None:
    versions = (ringrtc / 'config/version.properties').read_text()
    for required in ('webrtc.version=7778d', 'ringrtc.version.major=2', 'ringrtc.version.minor=69', 'ringrtc.version.revision=5'):
        if required not in versions.splitlines():
            raise RuntimeError(f'Unsupported dependency version: missing {required}')
    webrtc = ringrtc / 'src/webrtc/src'
    destination = webrtc / 'audio/molly_incoming'
    destination.mkdir(parents=True, exist_ok=True)
    for name in ('dsp.h', 'adapter.h', 'effect_scope.h'):
        shutil.copy2(sources / name, destination / name)
    header = webrtc / 'audio/audio_transport_impl.h'
    replace_once(header, '#include "api/audio/audio_mixer.h"', '#include "api/audio/audio_mixer.h"\n#include "audio/molly_incoming/adapter.h"')
    replace_once(header, '  AudioFrame mixed_frame_;', '  AudioFrame mixed_frame_;\n  molly_audio::ReceiveProcessor incoming_audio_;')
    source = webrtc / 'audio/audio_transport_impl.cc'
    for channels in ('nChannels', 'number_of_channels'):
        anchor = f'  mixer_->Mix({channels}, &mixed_frame_);'
        replace_once(source, anchor, anchor + '\n  incoming_audio_.Process(&mixed_frame_);')
    text = source.read_text()
    if 'MOLLY_INCOMING_AUDIO_EXPORTS' not in text:
        source.write_text(text + '\n' + (sources / 'exports.inc').read_text())
    gn = webrtc / 'audio/BUILD.gn'
    replace_once(gn, '"audio_transport_impl.h",', '"audio_transport_impl.h",\n    "molly_incoming/dsp.h",\n    "molly_incoming/adapter.h",')
    lib = ringrtc / 'src/rust/src/lib.rs'
    replace_once(lib, '        mod jni_call_manager;', '        mod jni_call_manager;\n        mod incoming_audio;')
    shutil.copy2(sources / 'incoming_audio.rs', ringrtc / 'src/rust/src/android/api/incoming_audio.rs')
    call_manager = ringrtc / 'src/android/api/org/signal/ringrtc/CallManager.java'
    initializer = """      CallManager.isInitialized = true;
      // App-owned local settings must be loaded even for background calls.
      try {
        Class.forName("org.thoughtcrime.securesms.webrtc.audio.IncomingAudioController")
          .getMethod("initialize", Context.class).invoke(null, applicationContext);
      } catch (ReflectiveOperationException error) {
        Log.w(TAG, "Incoming audio settings could not be initialized", error);
      }"""
    replace_once(call_manager, '      CallManager.isInitialized = true;', initializer)
    # Verify the final placement rather than merely checking that the edit ran.
    text = source.read_text()
    mix = text.index('mixer_->Mix(nChannels, &mixed_frame_)')
    hook = text.index('incoming_audio_.Process(&mixed_frame_)', mix)
    aec = text.index('ProcessReverseAudioFrame(audio_processing_, &mixed_frame_)', mix)
    if not mix < hook < aec:
        raise RuntimeError('Receive processor must precede echo-reference processing')
    print('Patched pinned receive path and JNI. Microphone/capture path unchanged.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('ringrtc', type=Path)
    parser.add_argument('--sources', type=Path, default=Path(__file__).resolve().parents[2] / 'native/incoming-audio')
    args = parser.parse_args()
    patch(args.ringrtc.resolve(), args.sources.resolve())
