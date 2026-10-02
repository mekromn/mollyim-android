#!/usr/bin/env python3
"""Pinned, fail-closed local call-engine extension. Applies after DeepFilterNet."""
from pathlib import Path
import argparse,importlib.util,shutil
ROOT=Path(__file__).resolve().parents[2]
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
base=load('deepfilter_patch',ROOT/'tools/deepfilter/patch_ringrtc.py')
once=base.once

def patch(ring:Path,root:Path=ROOT):
 web=ring/'src/webrtc/src';source=web/'audio/audio_transport_impl.cc';header=web/'audio/audio_transport_impl.h'
 if 'MOLLY_MOCK_CALL_EXPORTS' not in source.read_text():base.patch(ring,root)
 # Refresh only owned source files; upstream edits below have unique anchors.
 for folder,target in [('incoming-audio','molly_incoming'),('call-denoise','molly_denoise'),('mock-call','molly_mock')]:
  dst=web/'audio'/target;dst.mkdir(exist_ok=True)
  for p in (root/'native'/folder).iterdir():
   if p.suffix in ('.h','.cc'):shutil.copy2(p,dst/p.name)
 once(header,'#include "audio/molly_denoise/webrtc_adapter.h"','#include "audio/molly_mock/registry.h"\n#include "audio/molly_denoise/webrtc_adapter.h"')
 once(header,'  molly_audio::ReceiveProcessor incoming_audio_;\n  molly_denoise::WebRtcDenoiser denoiser_;','''  // Construction-only lifetime scope precedes and outlives every audio object.
  std::shared_ptr<molly_mock::LabSession> mock_session_ = molly_mock::AcquireConstructionSession();
  molly_denoise::WebRtcDenoiser denoiser_;
  molly_audio::ReceiveProcessor incoming_audio_{denoiser_.Effects()};
  std::unique_ptr<molly_mock::LabEndpoint> mock_;''')
 once(header,'  void MollyCaptureStopped() { denoiser_.CaptureStopped(); }','''  void MollyCaptureStopped() { denoiser_.CaptureStopped(); }
  void MollyMockReady(AudioDeviceModule* adm, TaskQueueBase* queue);
  void MollyMockStop();
  void MollyReplaySent(AudioFrame* frame);
  molly_denoise::DenoiseStatus MollyDenoiseStatus(molly_denoise::Direction d) const noexcept { return denoiser_.Status(d); }''')
 once(header,'  void SendProcessedData(std::unique_ptr<AudioFrame> audio_frame);','''  void SendProcessedData(std::unique_ptr<AudioFrame> audio_frame);
  std::optional<molly_denoise::CallTransport::Lease> MollyPrepareProcessedFrame(AudioFrame* frame);''')
 once(header,'class AudioSender;','class AudioSender;\nclass AudioDeviceModule;\nclass TaskQueueBase;')
 once(source,'#include "audio/audio_transport_impl.h"','#include "audio/audio_transport_impl.h"\n#include "audio/molly_mock/endpoint.h"')
 old='''  std::optional<molly_denoise::CallTransport::Lease> denoise_delivery;
  if (denoiser_.Bound()) {
    denoise_delivery.emplace(denoiser_.ProcessSent(audio_frame.get()));
    if (!*denoise_delivery) return;
  }'''
 new='''  std::optional<molly_mock::LabEndpoint::SendLease> mock_delivery;
  if (mock_) {
    mock_delivery.emplace(mock_->BeginSent(*audio_frame));
    if (!*mock_delivery) return;
  }
  auto denoise_delivery = MollyPrepareProcessedFrame(audio_frame.get());
  if (denoise_delivery && !*denoise_delivery) return;'''
 once(source,old,new)
 once(source,'AudioTransportImpl::~AudioTransportImpl() {}','''AudioTransportImpl::~AudioTransportImpl() {
  MollyMockStop();
  denoiser_.SetObserver(nullptr);
  mock_.reset();
}

std::optional<molly_denoise::CallTransport::Lease>
AudioTransportImpl::MollyPrepareProcessedFrame(AudioFrame* frame) {
  std::optional<molly_denoise::CallTransport::Lease> delivery;
  if (denoiser_.Bound()) delivery.emplace(denoiser_.ProcessSent(frame));
  return delivery;
}

void AudioTransportImpl::MollyReplaySent(AudioFrame* frame) {
  if (!mock_ || !denoiser_.Bound()) return;
  denoiser_.StampCapture(frame);
  auto mock_delivery = mock_->BeginSent(*frame);
  if (!mock_delivery) return;
  auto delivery = MollyPrepareProcessedFrame(frame);
  // The real sent-stage observer has already delivered float output to the
  // local sink. No allocation, codec, PeerConnection or network fan-out here.
  if (delivery && !*delivery) return;
}

void AudioTransportImpl::MollyMockReady(AudioDeviceModule* adm, TaskQueueBase* queue) {
  if (!mock_session_ || mock_) return;
  auto endpoint = std::make_unique<molly_mock::LabEndpoint>(mock_session_, *this, *mixer_, adm, queue);
  if (!endpoint->Attached()) return;
  mock_ = std::move(endpoint);
  denoiser_.SetObserver(mock_.get());
}

void AudioTransportImpl::MollyMockStop() {
  if (mock_) mock_->Stop();
}''')
 once(source,'  int send_sample_rate_hz = 0;','''  uint64_t mock_epoch = 0;
  if (mock_) {
    mock_epoch = mock_->CaptureInput(source.data(), sample_rate,
      static_cast<uint32_t>(number_of_channels), estimated_capture_time_ns.value_or(-1));
    if (!mock_epoch) return 0;
  }
  int send_sample_rate_hz = 0;''')
 once(source,'  std::unique_ptr<AudioFrame> audio_frame(new AudioFrame());','  std::unique_ptr<AudioFrame> audio_frame(new AudioFrame());\n  audio_frame->molly_mock_epoch_ = mock_epoch;')
 base.all_exact(source,'  incoming_audio_.Process(&mixed_frame_, denoiser_.Receiver());','''  incoming_audio_.Process(&mixed_frame_, denoiser_.Receiver(), mock_.get());
  if (mock_) mock_->AfterMix(mixed_frame_);''',2)
 frame_h=web/'api/audio/audio_frame.h';frame_cc=web/'api/audio/audio_frame.cc'
 once(frame_h,'  uint64_t molly_owner_ = 0;','  uint64_t molly_mock_epoch_ = 0;\n  uint64_t molly_owner_ = 0;')
 once(frame_cc,'  molly_owner_ = 0;','  molly_mock_epoch_ = 0;\n  molly_owner_ = 0;')
 once(frame_cc,'  molly_owner_ = src.molly_owner_;','  molly_mock_epoch_ = src.molly_mock_epoch_;\n  molly_owner_ = src.molly_owner_;')
 engine=web/'media/engine/webrtc_voice_engine.cc'
 once(engine,'#include "media/engine/webrtc_voice_engine.h"','#include "media/engine/webrtc_voice_engine.h"\n#include "audio/audio_transport_impl.h"')
 once(engine,'  adm()->RegisterAudioCallback(audio_state()->audio_transport());','''  adm()->RegisterAudioCallback(audio_state()->audio_transport());
  static_cast<AudioTransportImpl*>(audio_state()->audio_transport())->MollyMockReady(adm(), TaskQueueBase::Current());''')
 once(engine,'  // Stop AudioDevice.\n  adm()->StopPlayout();','''  // Stop AudioDevice.
  static_cast<AudioTransportImpl*>(audio_state()->audio_transport())->MollyMockStop();
  adm()->StopPlayout();''')
 gn=web/'audio/BUILD.gn';names=sorted(p.name for p in (root/'native/mock-call').iterdir() if p.suffix in ('.h','.cc'))
 block='    # MOLLY_MOCK_SOURCES_BEGIN\n'+''.join(f'    "molly_mock/{name}",\n' for name in names)+'    # MOLLY_MOCK_SOURCES_END'
 text=gn.read_text()
 if '# MOLLY_MOCK_SOURCES_BEGIN' in text:
  a=text.index('    # MOLLY_MOCK_SOURCES_BEGIN');b=text.index('    # MOLLY_MOCK_SOURCES_END',a)+len('    # MOLLY_MOCK_SOURCES_END');text=text[:a]+block+text[b:];gn.write_text(text)
 else:once(gn,'    "molly_incoming/dsp.h",','    "molly_incoming/dsp.h",\n    "molly_incoming/effect_scope.h",\n'+block)
 # JNI/Java lifecycle delegates to the same native factory; no PeerConnection.
 java=ring/'src/android/api/org/signal/ringrtc';manager=java/'CallManager.java'
 once(manager,'  class PeerConnectionFactoryOptions extends PeerConnectionFactory.Options {','  static class PeerConnectionFactoryOptions extends PeerConnectionFactory.Options {')
 before='''  private PeerConnectionFactory createPeerConnectionFactory(@Nullable EglBase     eglBase,
                                                            @NonNull  AudioConfig audioConfig) {'''
 after='''  private static PeerConnectionFactory createPeerConnectionFactory(@Nullable EglBase eglBase,
                                                                   @NonNull AudioConfig audioConfig) throws CallException {
    return createPeerConnectionFactory(eglBase, audioConfig, false);
  }

  static PeerConnectionFactory createMockPeerConnectionFactory(@NonNull AudioConfig config) throws CallException {
    checkInitializeHasBeenCalled();
    return createPeerConnectionFactory(null, config, true);
  }

  private static PeerConnectionFactory createPeerConnectionFactory(@Nullable EglBase eglBase,
                                                                   @NonNull AudioConfig audioConfig,
                                                                   boolean localMock) throws CallException {'''
 once(manager,before,after)
 once(manager,'    PeerConnectionFactory factory = CallDenoiseGate.createFactory(() -> PeerConnectionFactory.builder()', '    java.util.function.Supplier<PeerConnectionFactory> builder = () -> PeerConnectionFactory.builder()')
 once(manager,'            .createPeerConnectionFactory());\n    adm.release();\n    return factory;', '''            .createPeerConnectionFactory();
    try {
      return localMock ? builder.get() : CallDenoiseGate.createFactory(builder);
    } finally {
      adm.release();
    }''')
 # Direct/group public entry points already propagate checked CallException.
 once(manager,'                                boolean                        hideIp) {','                                boolean                        hideIp) throws CallException {')
 gate=java/'CallDenoiseGate.java'
 once(gate,'  static synchronized PeerConnectionFactory createFactory(Supplier<PeerConnectionFactory> builder) {','''  static synchronized PeerConnectionFactory createFactory(Supplier<PeerConnectionFactory> builder) throws CallException {
    MockCallSession.requireIdleHardware();''')
 once(gate,'  static synchronized long begin(PeerConnectionFactory factory) {','''  static synchronized long begin(PeerConnectionFactory factory) throws CallException {
    MockCallSession.requireIdleHardware();''')
 group=java/'GroupCall.java'
 once(group,'    private void startDenoise() {','    private void startDenoise() throws CallException {')
 shutil.copy2(root/'native/mock-call/java/MockCallSession.java',java/'MockCallSession.java')
 rust=ring/'src/rust/src';once(rust/'lib.rs','        mod call_denoise;','        mod call_denoise;\n        mod mock_call;')
 shutil.copy2(root/'native/mock-call/mock_call.rs',rust/'android/api/mock_call.rs')
 # Refresh the owned export block without re-running already patched anchors.
 text=source.read_text();marker='// MOLLY_MOCK_CALL_EXPORTS'
 if marker in text:text=text[:text.index(marker)].rstrip()+'\n'
 source.write_text(text+'\n'+(root/'native/mock-call/exports.inc').read_text())
 text=source.read_text();start=text.index('mixer_->Mix(nChannels')
 assert text.index('incoming_audio_.Process',start)<text.index('mock_->AfterMix',start)<text.index('ProcessReverseAudioFrame',start)
 assert text.count('MollyPrepareProcessedFrame(')==3
 assert text.index('mock_->CaptureInput')<text.index('  ProcessCaptureFrame(',text.index('mock_->CaptureInput'))
 print('Patched actual local call engine, scoped effects, capture/render taps and shared sent stage.')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('ringrtc',type=Path);a=p.parse_args();patch(a.ringrtc.resolve())
