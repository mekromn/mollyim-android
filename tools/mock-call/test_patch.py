#!/usr/bin/env python3
"""Exact upstream patch, idempotence, lifetime and hook ordering; not execution."""
from pathlib import Path
import hashlib,os,shutil,tempfile
import patch_ringrtc
ROOT=Path(__file__).resolve().parents[2]
web=Path(os.environ['WEBRTC_SOURCE']);ring=Path(os.environ['RINGRTC_SOURCE'])
files=['audio/audio_transport_impl.h','audio/audio_transport_impl.cc','audio/audio_state.cc','audio/BUILD.gn','api/audio/audio_frame.h','api/audio/audio_frame.cc','media/engine/webrtc_voice_engine.cc','pc/peer_connection_factory.cc','pc/peer_connection_factory.h','pc/BUILD.gn']
with tempfile.TemporaryDirectory() as td:
 dest=Path(td);shutil.copytree(ring,dest,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.git','out','build','target'))
 wd=dest/'src/webrtc/src'
 for path in files:
  p=wd/path;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(web/path,p)
 patch_ringrtc.patch(dest)
 def hashes():return {str(p.relative_to(dest)):hashlib.sha256(p.read_bytes()).hexdigest() for p in dest.rglob('*') if p.is_file()}
 first=hashes();patch_ringrtc.patch(dest);assert first==hashes(),'non-idempotent mock patch'
 header=(wd/files[0]).read_text();source=(wd/files[1]).read_text()
 assert header.index('mock_session_')<header.index('WebRtcDenoiser denoiser_')<header.index('incoming_audio_{denoiser_.Effects()}')<header.index('unique_ptr<molly_mock::LabEndpoint>')
 assert source.count('auto denoise_delivery = MollyPrepareProcessedFrame(audio_frame.get())')==1
 assert source.count('auto delivery = MollyPrepareProcessedFrame(frame)')==1
 assert source.index('mock_->CaptureInput')<source.index('  ProcessCaptureFrame(',source.index('mock_->CaptureInput'))
 assert 'molly_mock_epoch_ = src.molly_mock_epoch_' in (wd/'api/audio/audio_frame.cc').read_text()
 factory=(wd/'pc/peer_connection_factory.cc').read_text()
 assert 'std::make_unique<ConnectionContext::MediaEngineReference>(context_)' in factory
 assert 'molly_mock_media_engine_ref_ = nullptr;' in factory
 java=(dest/'src/android/api/org/signal/ringrtc/MockCallSession.java').read_text()
 assert '.createPeerConnection(' not in java and 'CallManager.createMockPeerConnectionFactory' in java
 assert 'synchronized (CallDenoiseGate.class)' in java and 'Await hardware retirement' in java
 assert 'private void startDenoise() throws CallException' in (dest/'src/android/api/org/signal/ringrtc/GroupCall.java').read_text()
 assert 'MockCallSession.requireIdleHardware' in (dest/'src/android/api/org/signal/ringrtc/CallDenoiseGate.java').read_text()
print('Pinned mock-call patch, shared hooks, scope lifetime and checked call preemption contracts pass.')
