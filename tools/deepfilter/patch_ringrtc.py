#!/usr/bin/env python3
"""Patch only the audited RingRTC/WebRTC revisions. No APK bytecode patching."""
from pathlib import Path
import argparse
import importlib.util
import shutil


def once(path: Path, before: str, after: str) -> None:
    text = path.read_text()
    if after in text:
        return
    if text.count(before) != 1:
        raise RuntimeError(f'Expected unique anchor in {path}: {before!r}')
    path.write_text(text.replace(before, after, 1))


def all_exact(path: Path, before: str, after: str, count: int) -> None:
    text = path.read_text()
    if text.count(after) == count and before not in text:
        return
    if text.count(before) != count:
        raise RuntimeError(f'Expected {count} anchors in {path}: {before!r}')
    path.write_text(text.replace(before, after))


def patch(ring: Path, root: Path) -> None:
    version = (ring / 'config/version.properties').read_text().splitlines()
    for line in ('webrtc.version=7778d', 'ringrtc.version.major=2', 'ringrtc.version.minor=69', 'ringrtc.version.revision=5'):
        if line not in version:
            raise RuntimeError(f'Unsupported source: {line}')
    web = ring / 'src/webrtc/src'
    source = web / 'audio/audio_transport_impl.cc'
    if 'MOLLY_CALL_DENOISE_EXPORTS' not in source.read_text():
        spec = importlib.util.spec_from_file_location('incoming_patch', root / 'tools/incoming-audio/patch_ringrtc.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        module.patch(ring, root / 'native/incoming-audio')
    # Source copies are refreshed even on repeated invocations; placement edits
    # below remain fail-closed and idempotent, not fuzzy line-number patches.
    incoming = web / 'audio/molly_incoming'
    for name in ('adapter.h', 'dsp.h', 'effect_scope.h'):
        shutil.copy2(root / 'native/incoming-audio' / name, incoming / name)
    dest = web / 'audio/molly_denoise'; dest.mkdir(exist_ok=True)
    native = root / 'native/call-denoise'
    native_names = sorted(p.name for p in native.iterdir() if p.suffix in ('.h', '.cc'))
    for name in native_names:
        shutil.copy2(native / name, dest / name)
    shutil.copy2(root / 'native/deepfilter-runtime/include/molly_deepfilter.h', dest / 'molly_deepfilter.h')

    header = web / 'audio/audio_transport_impl.h'
    once(header, '#include "audio/molly_incoming/adapter.h"', '#include "audio/molly_denoise/webrtc_adapter.h"\n#include "audio/molly_incoming/adapter.h"')
    once(header, '  molly_audio::ReceiveProcessor incoming_audio_;', '  molly_audio::ReceiveProcessor incoming_audio_;\n  molly_denoise::WebRtcDenoiser denoiser_;')
    once(header, '  ~AudioTransportImpl() override;', '  ~AudioTransportImpl() override;\n\n  void MollyCaptureStopped() { denoiser_.CaptureStopped(); }')
    once(source, '  voe::RemixAndResample(source, sample_rate, &capture_resampler_,', '  denoiser_.StampCapture(audio_frame.get());\n  voe::RemixAndResample(source, sample_rate, &capture_resampler_,')
    before = '''  TRACE_EVENT0("webrtc", "AudioTransportImpl::SendProcessedData");
  RTC_DCHECK_GT(audio_frame->samples_per_channel_, 0);'''
    after = before + '''
  std::optional<molly_denoise::CallTransport::Lease> denoise_delivery;
  if (denoiser_.Bound()) {
    denoise_delivery.emplace(denoiser_.ProcessSent(audio_frame.get()));
    if (!*denoise_delivery) return;
  }'''
    once(source, before, after)
    all_exact(source, '  incoming_audio_.Process(&mixed_frame_);', '  incoming_audio_.Process(&mixed_frame_, denoiser_.Receiver());', 2)
    if 'MOLLY_CALL_DENOISE_EXPORTS' not in source.read_text():
        source.write_text(source.read_text() + '\n' + (native / 'exports.inc').read_text())

    frame_h = web / 'api/audio/audio_frame.h'; frame_cc = web / 'api/audio/audio_frame.cc'
    once(frame_h, '  // RTP timestamp of the first sample in the AudioFrame.', '''  // Molly call provenance is captured before any asynchronous processing.
  uint64_t molly_owner_ = 0;
  uint64_t molly_gate_generation_ = 0;
  uint64_t molly_stream_generation_ = 0;
  int64_t molly_source_start_ = 0;
  void clear_absolute_capture_timestamp() { absolute_capture_timestamp_ms_ = std::nullopt; }

  // RTP timestamp of the first sample in the AudioFrame.''')
    fields = ('molly_owner_', 'molly_gate_generation_', 'molly_stream_generation_', 'molly_source_start_')
    once(frame_cc, '  timestamp_ = 0;', '  timestamp_ = 0;\n' + '\n'.join(f'  {f} = 0;' for f in fields))
    once(frame_cc, '  timestamp_ = src.timestamp_;', '  timestamp_ = src.timestamp_;\n' + '\n'.join(f'  {f} = src.{f};' for f in fields))
    state = web / 'audio/audio_state.cc'
    once(state, '    // Disable recording.\n    adm->StopRecording();', '    // Disable recording.\n    audio_transport_.MollyCaptureStopped();\n    adm->StopRecording();')
    once(state, '  if (sending_streams_.empty()) {\n    config_.audio_device_module->StopRecording();', '  if (sending_streams_.empty()) {\n    audio_transport_.MollyCaptureStopped();\n    config_.audio_device_module->StopRecording();')
    gn = web / 'audio/BUILD.gn'
    added = '\n'.join(f'    "molly_denoise/{name}",' for name in native_names + ['molly_deepfilter.h'])
    once(gn, '    "molly_incoming/adapter.h",', '    "molly_incoming/adapter.h",\n' + added)
    # dlopen is worker-only; libDF is separately packaged to avoid a Rust/C++
    # circular link dependency. Android already exposes libdl through libc++.
    once(gn, 'rtc_library("audio") {', 'rtc_library("audio") {\n  if (is_android || is_linux) { libs = [ "dl" ] }')

    java = ring / 'src/android/api/org/signal/ringrtc'
    shutil.copy2(native / 'java/CallDenoiseGate.java', java / 'CallDenoiseGate.java')
    manager = java / 'CallManager.java'
    once(manager, '  private long                                nativeCallManager;', '  private long                                nativeCallManager;\n  @Nullable private CallContext denoiseDirectContext;')
    once(manager, '    PeerConnectionFactory factory = PeerConnectionFactory.builder()', '    PeerConnectionFactory factory = CallDenoiseGate.createFactory(() -> PeerConnectionFactory.builder()')
    once(manager, '            .createPeerConnectionFactory();', '            .createPeerConnectionFactory());')
    once(manager, '    callContext.setVideoEnabled(enableCamera);', '    denoiseDirectContext = callContext;\n    callContext.setVideoEnabled(enableCamera);')
    once(manager, '    Connection connection = ringrtcGetActiveConnection(nativeCallManager);\n    connection.setAudioEnabled(enable);', '''    CallContext callContext = ringrtcGetActiveCallContext(nativeCallManager);
    CallDenoiseGate.mute(callContext.denoiseOwner, !enable, false);
    Connection connection = ringrtcGetActiveConnection(nativeCallManager);
    connection.setAudioEnabled(enable);''')
    for event in ('reset', 'hangup', 'close'):
        anchor = f'    Log.i(TAG, "{event}():");'
        extra = '\n    if (denoiseDirectContext != null) CallDenoiseGate.end(denoiseDirectContext.denoiseOwner);'
        if event == 'close':
            extra += '\n    for (int i = 0; i < groupCallByClientId.size(); i++) groupCallByClientId.valueAt(i).endDenoise();'
        once(manager, anchor, anchor + extra)
    once(manager, '    Log.i(TAG, "onCloseMedia():");', '    Log.i(TAG, "onCloseMedia():");\n    CallDenoiseGate.end(callContext.denoiseOwner);')
    once(manager, '  static class CallContext {', '  static class CallContext {\n    final long denoiseOwner;')
    once(manager, '    }\n\n    void setVideoEnabled(boolean enable) {', '      this.denoiseOwner = CallDenoiseGate.begin(factory);\n    }\n\n    void setVideoEnabled(boolean enable) {')
    once(manager, '      Log.i(TAG, "dispose(): " + callId);', '      Log.i(TAG, "dispose(): " + callId);\n      CallDenoiseGate.end(denoiseOwner);')

    group = java / 'GroupCall.java'
    once(group, '    private GroupCall(@NonNull  Kind', '''    private long denoiseOwner;
    private void startDenoise() {
        if (denoiseOwner == 0) denoiseOwner = CallDenoiseGate.begin(factory);
        CallDenoiseGate.mute(denoiseOwner, localDeviceState.audioMuted, false);
    }
    void endDenoise() {
        CallDenoiseGate.end(denoiseOwner);
        denoiseOwner = 0;
    }

    private GroupCall(@NonNull  Kind''')
    for event in ('connect', 'join'):
        anchor=f'        Log.i(TAG, "{event}():");';once(group,anchor,anchor+'\n        startDenoise();')
    for event in ('leave', 'disconnect', 'dispose', 'handleEnded'):
        anchor=f'        Log.i(TAG, "{event}():");';once(group,anchor,anchor+'\n        endDenoise();')
    once(group, '        Log.i(TAG, "setOutgoingAudioMuted():");', '        Log.i(TAG, "setOutgoingAudioMuted():");\n        CallDenoiseGate.mute(denoiseOwner, muted, false);')
    once(group, '        Log.i(TAG, "setOutgoingAudioMutedRemotely():");', '        Log.i(TAG, "setOutgoingAudioMutedRemotely():");\n        CallDenoiseGate.mute(denoiseOwner, true, true);')
    rust = ring / 'src/rust/src'
    once(rust / 'lib.rs', '        mod incoming_audio;', '        mod incoming_audio;\n        mod call_denoise;')
    shutil.copy2(native / 'call_denoise.rs', rust / 'android/api/call_denoise.rs')
    # This is merely a location proof; native compilation and sentinel tests
    # remain independent mandatory checks.
    text=source.read_text();start=text.index('mixer_->Mix(nChannels')
    assert text.index('incoming_audio_.Process',start)<text.index('ProcessReverseAudioFrame',start)
    assert text.count('incoming_audio_.Process(&mixed_frame_, denoiser_.Receiver());')==2
    assert text.index('denoiser_.StampCapture')<text.index('  ProcessCaptureFrame(',text.index('denoiser_.StampCapture'))
    print('Patched pinned send/receive hooks, factory ownership, mute and capture-stop boundaries.')

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('ringrtc',type=Path);args=parser.parse_args()
    patch(args.ringrtc.resolve(),Path(__file__).resolve().parents[2])
