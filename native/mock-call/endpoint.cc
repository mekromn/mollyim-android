/* SPDX-License-Identifier: AGPL-3.0-only */
#include "endpoint.h"
#include "audio/audio_transport_impl.h"
#include "audio/utility/audio_frame_operations.h"
#include <chrono>
#include <cmath>
namespace molly_mock {
namespace {
int64_t NowNs()noexcept{return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
void CopyPcm(webrtc::AudioFrame&frame,std::span<const float>pcm,uint32_t rate,uint32_t channels)noexcept {
 if((channels!=1&&channels!=2)||(rate!=8000&&rate!=16000&&rate!=32000&&rate!=48000)||pcm.size()!=static_cast<size_t>(rate/100)*channels){frame.Mute();return;}
 frame.SetSampleRateAndChannelSize(rate);auto data=frame.mutable_data(rate/100,channels);
 for(size_t i=0;i<pcm.size();++i)data[i]=static_cast<int16_t>(std::lrint(std::clamp(pcm[i],-1.f,32767.f/32768)*32768));
}
}
LabEndpoint::LabEndpoint(std::shared_ptr<LabSession>s,webrtc::AudioTransportImpl&t,webrtc::AudioMixer&m,webrtc::AudioDeviceModule*adm,webrtc::TaskQueueBase*q)
 :session_(std::move(s)),transport_(t),mixer_(m),adm_(adm) {
 if(!session_||!adm_||!q||q!=webrtc::TaskQueueBase::Current()||!session_->service.Local())return;
 std::lock_guard lock(session_->command_lock);
 if(session_->endpoint||session_->state.Revoked())return;
 session_->endpoint=this;session_->queue=q;attached_=true;
}
LabEndpoint::~LabEndpoint(){Stop();if(attached_){std::lock_guard lock(session_->command_lock);session_->endpoint=nullptr;session_->queue=nullptr;}}
void LabEndpoint::Stop(){
 if(!attached_)return;
 session_->state.Quiesce();
 // VoiceEngine terminates on its worker before AudioState destruction. A
 // second stop during destruction must not call an ADM on a different queue.
 if(session_->hardware_stopped.load()&&!source_added_)return;
 const bool input_stopped=!adm_||adm_->StopRecording()==0;
 const bool output_stopped=!adm_||adm_->StopPlayout()==0;
 transport_.UpdateAudioSenders({},8000,1);
 if(source_added_){mixer_.RemoveSource(this);source_added_=false;}
 if(session_->owner)session_->service.SetGate(session_->owner,false,molly_denoise::GateReason::Mute);
 session_->hardware_stopped.store(input_stopped&&output_stopped);session_->warming.store(false);
 if(!input_stopped||!output_stopped){session_->error.store(21);session_->status.store(5);return;}
 if(!session_->state.Revoked()&&session_->status.load()!=5)session_->status.store(3);
}
void LabEndpoint::ApplyEffective(){
 auto c=session_->requested;
 if(mode_==0)c.sent.enabled=false;
 if(mode_==1){c.received.enabled=false;c.effects.enabled=false;}
 session_->scope.Configure(c.received,c.sent,c.effects);
}
bool LabEndpoint::Start(int mode,bool live,int monitor,uint32_t taps){
 if(!attached_||session_->state.Revoked()||mode<0||mode>2||monitor<0||monitor>1||taps>31)return false;
 // The first native endpoint supports record-then-play and independent echo
 // playback. Live sent monitoring is never admitted on an unverified route.
 if(live&&monitor==1)return false;
 Stop();if(!session_->hardware_stopped.load())return false;session_->state.Stop();mode_=mode;live_=live;monitor_=mode==0?0:(mode==1?1:monitor);
 if(live_&&mode_==1)monitor_=0; // silent render, not microphone feedback
 if(live_&&mode_==0)return false;
 if(!live_&&mode_!=1&&!session_->clips[0].Rate())return false;
 if(!live_&&mode_!=0&&!session_->clips[1].Rate())return false;
 if(mode_==2&&!session_->clips[0].Rate())return false;
 ApplyEffective();session_->scope.effects.reset_epoch.fetch_add(1);
 session_->service.Invalidate(session_->owner,molly_denoise::GateReason::Route);
 if(!session_->service.SetGate(session_->owner,mode_!=0,molly_denoise::GateReason::Mute))return false;
 segment_=std::max<uint64_t>(segment_,1);render_tick_=raw_position_=preroll_tick_=0;started_preroll_=live_;
 origins_={0,0};last_input_={-480,-480};complete_={false,false};for(auto& h:dry_)h.Clear();received_original_={};
 TapPacket stale;while(sent_monitor_.Pop(stale)){}
 if(!session_->state.Start(segment_,taps))return false;
 session_->error.store(0);session_->mode.store(mode_);session_->finished.store(false);session_->warming.store(!live_);session_->position_ms.store(start_ms_);
 if(!mixer_.AddSource(this)){session_->state.Stop();return false;}source_added_=true;
 // No AudioSendStream/PeerConnection is fabricated. This local sink is the
 // only sender in this explicitly local AudioTransport.
 const uint32_t send_rate=live_?48000:std::max<uint32_t>(8000,session_->clips[1].Rate());
 const uint32_t send_channels=live_?2:std::max<uint32_t>(1,session_->clips[1].Channels());
 transport_.UpdateAudioSenders(mode_==0?std::vector<webrtc::AudioSender*>{}:std::vector<webrtc::AudioSender*>{this},send_rate,send_channels);
 session_->hardware_stopped.store(false);
 if(session_->state.Revoked()||adm_->InitPlayout()!=0||adm_->StartPlayout()!=0){Stop();return false;}
 if(live_&&(session_->state.Revoked()||adm_->InitRecording()!=0||adm_->StartRecording()!=0)){Stop();return false;}
 session_->status.store(2);return true;
}
bool LabEndpoint::Execute(int op,int64_t a,int64_t b,int64_t c,int64_t d){
 if(!attached_)return false;
 if(op==2){Stop();return session_->hardware_stopped.load();}
 if(session_->state.Revoked())return false;
 switch(op){
 case 1:return a>=0&&a<=2&&b>=0&&b<=1&&c>=0&&c<=1&&d>=0&&d<=31&&Start(static_cast<int>(a),b!=0,static_cast<int>(c),static_cast<uint32_t>(d));
 case 3:if(a<0||a>1)return false;session_->service.SetGate(session_->owner,a==0,molly_denoise::GateReason::Mute);session_->state.Mute(a!=0);return true;
 case 4:Stop();session_->state.Stop();if(a<=0)return false;segment_=static_cast<uint64_t>(a);return true;
 case 5:if(a<0||a>1)return false;original_.store(a!=0);return true;
 case 6: // selection changes never mutate a source while a callback borrows it
   if(!session_->hardware_stopped.load()||a<0||b<=a||b>120000)return false;start_ms_=a;end_ms_=b;return true;
 case 8:if(a<0||a>1||b<0||b>1)return false;session_->scope.denoise.Bypass(static_cast<molly_denoise::Direction>(a),b!=0);return true;
 case 9:if(a<0||a>1)return false;session_->scope.denoise.Retry(static_cast<molly_denoise::Direction>(a));return true;
 case 10:if(a<0||a>1||live_)return false;monitor_=static_cast<int>(a);return true;
 default:return false;
 }
}
int LabEndpoint::PreferredSampleRate()const{return mode_==1?48000:static_cast<int>(std::max<uint32_t>(8000,session_->clips[0].Rate()));}
bool LabEndpoint::ReadyForPreroll()const noexcept {
 for(int i=0;i<2;++i){if((i==0&&mode_==1)||(i==1&&mode_==0))continue;
  const auto status=transport_.MollyDenoiseStatus(static_cast<molly_denoise::Direction>(i));
  if(status.state==molly_denoise::EffectiveState::Loading)return false;
 }
 return true;
}
void LabEndpoint::FillFrame(const Clip&clip,int64_t position,uint32_t rate,webrtc::AudioFrame&frame){
 const auto channels=clip.Channels()?clip.Channels():1u;const auto frames=rate/100;
 std::fill(clip_scratch_.begin(),clip_scratch_.end(),0);
 if(clip.Rate()==rate&&position<static_cast<int64_t>(end_ms_)*rate/1000)clip.Read(position,frames,clip_scratch_);
 frame.UpdateFrame(0,clip_scratch_.data(),frames,rate,webrtc::AudioFrame::kNormalSpeech,webrtc::AudioFrame::kVadUnknown,channels);
 frame.elapsed_time_ms_=position*1000/rate;frame.ntp_time_ms_=-1;frame.set_absolute_capture_timestamp_ms(NowNs()/1000000);
}
webrtc::AudioMixer::Source::AudioFrameInfo LabEndpoint::GetAudioFrameWithInfo(int rate,webrtc::AudioFrame* frame){
 render_epoch_=session_->state.Epoch();received_original_={};
 if(!session_->state.Allowed(render_epoch_)){frame->UpdateFrame(0,nullptr,rate/100,rate,webrtc::AudioFrame::kNormalSpeech,webrtc::AudioFrame::kVadUnknown,1);return AudioFrameInfo::kMuted;}
 if(!live_&&!started_preroll_&&render_tick_>0&&ReadyForPreroll()){
   started_preroll_=true;preroll_tick_=render_tick_;
   origins_[0]=last_input_[0]+rate/100;
   const auto sr=std::max<uint32_t>(8000,session_->clips[1].Rate());origins_[1]=last_input_[1]+sr/100;
 }
 const int64_t relative=started_preroll_?(render_tick_-preroll_tick_)*10+start_ms_-1000:-1000000;
 if(!live_&&mode_!=0){
   const auto&clip=session_->clips[1];const auto sr=clip.Rate();
   FillFrame(clip,relative*sr/1000,sr,replay_frame_);replay_frame_.molly_mock_epoch_=render_epoch_;
   transport_.MollyReplaySent(&replay_frame_);
 }
 const auto&clip=session_->clips[0];
 if(mode_==1){frame->UpdateFrame(0,nullptr,rate/100,rate,webrtc::AudioFrame::kNormalSpeech,webrtc::AudioFrame::kVadUnknown,1);}
 else FillFrame(clip,(live_?render_tick_*10+start_ms_:relative)*rate/1000,static_cast<uint32_t>(rate),*frame);
 ++render_tick_;
 if(!live_&&started_preroll_){session_->warming.store(relative<start_ms_);session_->position_ms.store(std::clamp<int64_t>(relative,start_ms_,end_ms_));}
 return frame->muted()?AudioFrameInfo::kMuted:AudioFrameInfo::kNormal;
}
bool LabEndpoint::AcceptsCapture()const noexcept {return live_&&!session_->state.Muted()&&session_->state.Allowed(session_->state.Epoch());}
uint64_t LabEndpoint::CaptureInput(std::span<const int16_t> samples,uint32_t rate,uint32_t channels,int64_t timestamp)noexcept {
 const auto epoch=session_->state.Epoch();if(!AcceptsCapture())return 0;
 raw_work_={};raw_work_.epoch=epoch;raw_work_.segment=segment_;raw_work_.tap=Tap::BackendInput;raw_work_.rate=rate;raw_work_.channels=channels;
 raw_work_.frames=rate/100;raw_work_.source_start=raw_position_;raw_work_.time_ns=timestamp;raw_work_.flags=1;raw_work_.configuration=session_->scope.Revision();
 const auto count=raw_work_.Count();if(count&&count==samples.size()){
  for(size_t i=0;i<count;++i)raw_work_.pcm[i]=samples[i]/32768.f;
  session_->state.Observe(raw_work_);raw_position_+=raw_work_.frames;
 }
 return session_->state.Allowed(epoch)?epoch:0;
}
LabEndpoint::SendLease LabEndpoint::BeginSent(const webrtc::AudioFrame&frame)noexcept {
 if(!session_->state.Allowed(frame.molly_mock_epoch_)||session_->state.Muted()||sent_busy_.test_and_set())return {};
 send_epoch_=frame.molly_mock_epoch_;return SendLease(sent_busy_);
}
void LabEndpoint::Before(bool received,const molly_denoise::OriginalPacket&p)noexcept {
 const auto i=received?0u:1u;dry_[i].Store(p);last_input_[i]=p.meta.source_start;
 if(!received){
  send_work_={};send_work_.epoch=send_epoch_;send_work_.segment=segment_;send_work_.configuration=session_->scope.Revision();send_work_.tap=Tap::BeforeSent;
  send_work_.rate=p.meta.rate;send_work_.channels=p.meta.channels;send_work_.frames=p.meta.frames;
  send_work_.source_start=ClipPosition(false,p.meta);send_work_.time_ns=p.meta.capture_time_ns;send_work_.flags=1;
  for(size_t n=0;n<p.meta.samples();++n)send_work_.pcm[n]=p.pcm[n]/32768.f;
  if(!InSelection(false,send_work_.source_start))send_work_.flags|=2;
  session_->state.Observe(send_work_);
 }
}
int64_t LabEndpoint::ClipPosition(bool received,const molly_denoise::PacketMeta&meta)const noexcept {
 if(live_)return meta.source_start;
 if(!started_preroll_)return -static_cast<int64_t>(meta.rate)*1000;
 return meta.source_start-origins_[received?0:1]+(start_ms_-1000)*meta.rate/1000;
}
bool LabEndpoint::InSelection(bool received,int64_t position)const noexcept {
 const auto rate=live_?48000:session_->clips[received?0:1].Rate();
 return live_||(started_preroll_&&rate&&position>=start_ms_*rate/1000&&position<end_ms_*rate/1000);
}
void LabEndpoint::Publish(Tap tap,const molly_denoise::PacketMeta&meta,std::span<const float>samples,uint64_t epoch,uint32_t flags)noexcept {
 auto&packet=tap==Tap::AfterSent?send_work_:render_work_;
 packet={};packet.epoch=epoch;packet.segment=segment_;packet.tap=tap;packet.configuration=session_->scope.Revision();packet.rate=meta.rate;packet.channels=meta.channels;packet.frames=meta.frames;
 packet.source_start=ClipPosition(tap==Tap::ReceivedResult,meta);packet.time_ns=meta.capture_time_ns;packet.flags=flags;
 if(packet.Count()!=samples.size())return;
 std::copy(samples.begin(),samples.end(),packet.pcm.begin());
 if(!InSelection(tap==Tap::ReceivedResult,packet.source_start))packet.flags|=2;
 session_->state.Observe(packet);
}
void LabEndpoint::After(bool received,const molly_denoise::ProcessResult&r,std::span<const float>samples)noexcept {
 const uint64_t epoch=received?render_epoch_:send_epoch_;
 if(!session_->state.Allowed(epoch))return;
 Publish(received?Tap::ReceivedResult:Tap::AfterSent,r.source,samples,epoch,r.kind==molly_denoise::OutputKind::Wet?0u:4u);
 auto&packet=received?render_work_:send_work_;
 const auto i=received?0u:1u;
 if(!live_&&started_preroll_&&packet.rate&&packet.source_start>=end_ms_*packet.rate/1000)complete_[i]=true;
 if(original_.load()){
   molly_denoise::OriginalPacket original;
   if(dry_[i].Find(r.source,original))for(size_t n=0;n<samples.size();++n)packet.pcm[n]=original.pcm[n]/32768.f;
   else std::fill(packet.pcm.begin(),packet.pcm.end(),0.f);
 }
 if(received){received_original_=packet;}
 else if(!live_){if(!sent_monitor_.Push(packet)){/* bounded audition loss, surfaced below */session_->error.store(20);}}
 if(!live_&&((mode_==0&&complete_[0])||(mode_==1&&complete_[1])||(mode_==2&&complete_[0]&&complete_[1])))session_->finished.store(true);
}
void LabEndpoint::SendAudioData(std::unique_ptr<webrtc::AudioFrame> frame){
 // Actual SendProcessedData fan-out arrives here; no encoder or network sender.
 // Float observations were already made at the real sent hook before PCM.
 (void)frame;
}
void LabEndpoint::AfterMix(webrtc::AudioFrame&frame)noexcept {
 const auto output_channels=frame.num_channels();
 if(!session_->state.Allowed(render_epoch_)){frame.Mute();return;}
 TapPacket monitored;
 if(monitor_==1&&!live_){
   bool found=false;
   for(unsigned n=0;n<16;++n){if(!sent_monitor_.Pop(monitored))break;if(monitored.epoch==render_epoch_){found=true;break;}}
   if(!found||(monitored.flags&2))frame.Mute();
   else CopyPcm(frame,std::span(monitored.pcm).first(monitored.Count()),monitored.rate,monitored.channels);
 }else{
   // Keep the unmonitored sent queue bounded; Both always runs both processors.
   for(unsigned n=0;n<16;++n){if(!sent_monitor_.Pop(monitored))break;}
   if((!live_&&(received_original_.flags&2))||(live_&&mode_==1))frame.Mute();
   else if(original_.load()&&received_original_.epoch==render_epoch_)CopyPcm(frame,std::span(received_original_.pcm).first(received_original_.Count()),received_original_.rate,received_original_.channels);
 }
 if(frame.num_channels()>output_channels)webrtc::AudioFrameOperations::DownmixChannels(output_channels,&frame);
 else if(frame.num_channels()<output_channels)webrtc::AudioFrameOperations::UpmixChannels(output_channels,&frame);
 // The actual audible signal is tapped before the unchanged echo reference.
 render_work_={};render_work_.tap=Tap::PlaybackReference;render_work_.epoch=render_epoch_;render_work_.segment=segment_;render_work_.configuration=session_->scope.Revision();
 render_work_.rate=frame.sample_rate_hz();render_work_.channels=static_cast<uint32_t>(frame.num_channels());render_work_.frames=static_cast<uint32_t>(frame.samples_per_channel());
 render_work_.source_start=(render_tick_-1)*render_work_.frames;render_work_.time_ns=NowNs();render_work_.flags=1;
 const auto data=frame.data_view();if(render_work_.Count()==data.size()){
  for(size_t i=0;i<data.size();++i)render_work_.pcm[i]=data[i]/32768.f;
  session_->state.Observe(render_work_);
 }
}
}
