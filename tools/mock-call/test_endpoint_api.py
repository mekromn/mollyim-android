#!/usr/bin/env python3
"""Endpoint scheduling against API fixtures. NOT real WebRTC/Android execution."""
from pathlib import Path
import os,subprocess,sys
R=Path(__file__).resolve().parents[2]
W=R/'out/mock-endpoint-api';W.mkdir(parents=True,exist_ok=True)
def write(path,text):
 p=W/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
for src,dst in [('call-denoise','molly_denoise'),('incoming-audio','molly_incoming'),('mock-call','molly_mock')]:
 p=W/'audio'/dst;p.parent.mkdir(exist_ok=True)
 if not p.exists():p.symlink_to(R/'native'/src,target_is_directory=True)
write('api/audio/audio_frame.h',r'''#pragma once
#include <array>
#include <span>
#include <optional>
#include <cstdint>
#include <algorithm>
namespace webrtc {
class AudioFrame {
 public:
 enum:size_t{kMaxDataSizeSamples=7680};enum SpeechType{kNormalSpeech};enum VADActivity{kVadUnknown};
 uint64_t molly_mock_epoch_=0,molly_owner_=0,molly_gate_generation_=0,molly_stream_generation_=0;
 int64_t molly_source_start_=0,elapsed_time_ms_=-1,ntp_time_ms_=-1;
 size_t samples_per_channel_=480,num_channels_=1;int sample_rate_hz_=48000;bool mute=true;
 std::array<int16_t,7680>buffer{};std::optional<int64_t>time;
 void UpdateFrame(uint32_t,const int16_t* data,size_t frames,int rate,SpeechType,VADActivity,size_t channels){sample_rate_hz_=rate;samples_per_channel_=frames;num_channels_=channels;mute=data==nullptr;if(data)std::copy_n(data,frames*channels,buffer.begin());else buffer.fill(0);}
 void SetSampleRateAndChannelSize(int rate){sample_rate_hz_=rate;samples_per_channel_=rate/100;}
 size_t samples_per_channel()const{return samples_per_channel_;}size_t num_channels()const{return num_channels_;}int sample_rate_hz()const{return sample_rate_hz_;}
 bool muted()const{return mute;}void Mute(){buffer.fill(0);mute=true;}
 auto data_view()const{return std::span(buffer).first(samples_per_channel_*num_channels_);}
 auto mutable_data(size_t frames,size_t channels){samples_per_channel_=frames;num_channels_=channels;mute=false;return std::span(buffer).first(frames*channels);}
 void CopyFrom(const AudioFrame&other){*this=other;}
 auto absolute_capture_timestamp_ms()const{return time;}void set_absolute_capture_timestamp_ms(int64_t t){time=t;}void clear_absolute_capture_timestamp(){time.reset();}
};}
''')
write('api/audio/audio_device.h',r'''#pragma once
namespace webrtc {class AudioDeviceModule {public:virtual ~AudioDeviceModule()=default;virtual int InitPlayout()=0;virtual int StartPlayout()=0;virtual int StopPlayout()=0;virtual int InitRecording()=0;virtual int StartRecording()=0;virtual int StopRecording()=0;};}
''')
write('api/audio/audio_mixer.h',r'''#pragma once
#include "api/audio/audio_frame.h"
namespace webrtc {class AudioMixer {public:class Source {public:enum class AudioFrameInfo{kNormal,kMuted,kError};virtual ~Source()=default;virtual AudioFrameInfo GetAudioFrameWithInfo(int,AudioFrame*)=0;virtual int Ssrc()const=0;virtual int PreferredSampleRate()const=0;};virtual ~AudioMixer()=default;virtual bool AddSource(Source*)=0;virtual void RemoveSource(Source*)=0;};}
''')
write('api/task_queue/task_queue_base.h',r'''#pragma once
#include <functional>
#include <deque>
namespace webrtc {class TaskQueueBase {public:static thread_local TaskQueueBase*current;std::deque<std::function<void()>>tasks;static TaskQueueBase*Current(){return current;}template<class F>void PostTask(F fn){tasks.emplace_back(std::move(fn));}void Pump(){current=this;while(!tasks.empty()){auto fn=std::move(tasks.front());tasks.pop_front();fn();}}};inline thread_local TaskQueueBase*TaskQueueBase::current=nullptr;}
''')
write('call/audio_sender.h',r'''#pragma once
#include "api/audio/audio_frame.h"
#include <memory>
namespace webrtc {class AudioSender{public:virtual ~AudioSender()=default;virtual void SendAudioData(std::unique_ptr<AudioFrame>)=0;};}
''')
write('audio/utility/audio_frame_operations.h',r'''#pragma once
#include "api/audio/audio_frame.h"
namespace webrtc {struct AudioFrameOperations{static void DownmixChannels(size_t ch,AudioFrame*f){for(size_t i=0;i<f->samples_per_channel_;++i)f->buffer[i]=static_cast<int16_t>((int(f->buffer[i*2])+f->buffer[i*2+1])/2);f->num_channels_=ch;}static void UpmixChannels(size_t ch,AudioFrame*f){for(size_t i=f->samples_per_channel_;i-->0;)for(size_t c=0;c<ch;++c)f->buffer[i*ch+c]=f->buffer[i];f->num_channels_=ch;}};}
''')
write('audio/audio_transport_impl.h',r'''#pragma once
#include "audio/molly_denoise/webrtc_adapter.h"
#include "audio/molly_incoming/adapter.h"
#include "audio/molly_mock/endpoint.h"
#include <vector>
namespace webrtc {class AudioTransportImpl {public:
 std::shared_ptr<molly_mock::LabSession> session=molly_mock::AcquireConstructionSession();
 molly_denoise::WebRtcDenoiser denoiser; molly_audio::ReceiveProcessor received{denoiser.Effects()};
 molly_mock::LabEndpoint*mock=nullptr;std::vector<AudioSender*>senders;
 void UpdateAudioSenders(std::vector<AudioSender*>v,int,size_t){senders=std::move(v);}
 molly_denoise::DenoiseStatus MollyDenoiseStatus(molly_denoise::Direction d)const noexcept{return denoiser.Status(d);}
 void MollyReplaySent(AudioFrame*f){if(!mock)return;denoiser.StampCapture(f);auto own=mock->BeginSent(*f);if(!own)return;auto lease=denoiser.ProcessSent(f);}
 void Render(AudioFrame*f){received.Process(f,denoiser.Receiver(),mock);mock->AfterMix(*f);}
};}
''')
flags=[os.environ.get('CXX','g++'),'-std=c++20','-O1','-g','-pthread','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-fsanitize=address,undefined','-fno-omit-frame-pointer','-DWEBRTC_POSIX','-DWEBRTC_LINUX','-I',str(W),'-I',str(R/'native/call-denoise'),'-I',str(R/'native/mock-call'),'-I',str(R/'native/deepfilter-runtime/include')]
web=Path(os.environ['WEBRTC_SOURCE'])
flags+=['-I',str(R/'native/call-denoise/tests/support'),'-I',str(web)]
files=[R/f'native/mock-call/{n}.cc' for n in ('scope','state','registry','endpoint')]+[R/f'native/call-denoise/{n}.cc' for n in ('control','library','worker','timeline','gate','transport','service')]
files += [web/p for p in ('common_audio/resampler/push_sinc_resampler.cc','common_audio/resampler/sinc_resampler.cc','common_audio/resampler/sinc_resampler_sse.cc','common_audio/resampler/sinc_resampler_avx2.cc','common_audio/audio_util.cc','rtc_base/memory/aligned_malloc.cc')]
files += [R/'native/call-denoise/tests/support/platform.cc',R/'native/mock-call/tests/endpoint_api_test.cc']
if os.environ.get('MOCK_STRICT')=='1':
 strict=['-Wunsafe-buffer-usage','-Wexit-time-destructors','-Wglobal-constructors','-fno-exceptions','-fno-rtti']
 # New production files are checked with Chromium's strict flags. Upstream
 # converters retain their upstream compile policy; sanitizer execution below
 # still covers them. Do not globally weaken the production compilation.
 for f in files:
  if f.is_relative_to(R/'native') and '/tests/' not in str(f):
   subprocess.run(flags+strict+['-c',str(f),'-o',str(W/(f.stem+'.strict.o'))],check=True)
 print('Strict scoped native endpoint/source compile passed (API fixture, not Android).')
 sys.exit(0)
subprocess.run(flags+[str(f) for f in files]+['-mavx2','-mfma','-ldl','-o',str(W/'tests')],check=True)
subprocess.run([str(W/'tests')],check=True)
