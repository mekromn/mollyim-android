/* SPDX-License-Identifier: AGPL-3.0-only */
#include "registry.h"
#include "endpoint.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <map>
#include <thread>
namespace molly_mock {
namespace {
struct Registry {std::mutex mutex;std::map<uint64_t,std::shared_ptr<LabSession>>sessions;uint64_t next=1;};
Registry& All(){static auto*const r=new Registry();return *r;}
template<class F>uint64_t Post(const std::shared_ptr<LabSession>&s,F fn,bool allow_revoked=false){
 if(!s)return 0;std::lock_guard lock(s->command_lock);
 if(!s->endpoint||!s->queue||(!allow_revoked&&s->state.Revoked()))return 0;
 const auto command=s->next_command.fetch_add(1);if(!command||command>=INT64_MAX)return 0;
 s->queue->PostTask([s,command,fn=std::move(fn),allow_revoked]()mutable{
  std::lock_guard inner(s->command_lock);
  if(!s->endpoint){s->ack.store(command);return;}
  if(!allow_revoked&&s->state.Revoked()){s->endpoint->Stop();s->ack.store(command);return;}
  const bool success=fn(*s->endpoint);
  if(s->state.Revoked()){s->endpoint->Stop();s->status.store(4);}
  else if(!success){s->error.store(1);s->status.store(5);}
  s->ack.store(command);
 });return command;
}
void StatusFields(LabSession&s,int direction,std::span<float>out){
 const auto d=static_cast<molly_denoise::Direction>(direction);const auto value=s.service.Status(d);
 molly_denoise::ControlSnapshot c;s.scope.denoise.Read(d,c);
 const std::array<float,16>values{static_cast<float>(value.state),static_cast<float>(value.rate),static_cast<float>(value.channels),value.rate?1000.f*value.delay_samples/value.rate:0,static_cast<float>(value.misses),value.input_peak,value.output_peak,value.snr,value.mean_us,value.p95_us,value.levels_valid?1.f:0.f,value.inference_valid?1.f:0.f,static_cast<float>(value.processed),0,c.bypassed?1.f:0.f,0};
 std::copy(values.begin(),values.end(),out.begin());
}
}
std::shared_ptr<LabSession>FindSession(uint64_t id){auto&r=All();std::lock_guard lock(r.mutex);const auto i=r.sessions.find(id);return i==r.sessions.end()?nullptr:i->second;}
std::shared_ptr<LabSession>AcquireConstructionSession(){
 auto&service=molly_denoise::ConstructionService();if(!service.Local())return {};
 auto&r=All();std::lock_guard lock(r.mutex);for(const auto&[id,s]:r.sessions){(void)id;if(&s->service==&service)return s;}return {};
}
uint64_t PrepareSession(){
 if(molly_denoise::GlobalService().HasActiveSession())return 0;
 auto&r=All();std::lock_guard lock(r.mutex);
 if(!r.sessions.empty()||r.next>=INT64_MAX)return 0;
 auto s=std::make_shared<LabSession>(r.next++);r.sessions.emplace(s->id,s);
 if(!molly_denoise::SelectConstructionService(&s->service)){r.sessions.erase(s->id);return 0;}
 s->factory=s->service.BeginFactory();if(!s->factory){molly_denoise::ClearConstructionService(&s->service);r.sessions.erase(s->id);return 0;}
 return s->id;
}
bool FinishSession(uint64_t id){
 auto s=FindSession(id);if(!s)return false;
 const bool bound=s->service.FinishFactory(s->factory);
 const bool cleared=molly_denoise::ClearConstructionService(&s->service);
 std::lock_guard lock(s->command_lock);
 if(!bound||!cleared||!s->endpoint||!s->endpoint->Attached()||s->state.Revoked()||molly_denoise::GlobalService().HasActiveSession())return false;
 s->owner=s->service.NewOwner();if(!s->service.BeginSession(s->owner,s->factory))return false;
 s->status.store(1);return true;
}
void RevokeSession(uint64_t id){
 auto s=FindSession(id);if(!s)return;s->state.Revoke();s->status.store(4);
 // Do not wait for a command currently stopping a driver. Every accepted
 // command rechecks revoked and performs hardware retirement on its queue.
 std::unique_lock lock(s->command_lock,std::try_to_lock);
 if(!lock.owns_lock()||!s->endpoint||!s->queue)return;
 if(s->queue==webrtc::TaskQueueBase::Current()){s->endpoint->Stop();return;}
 s->queue->PostTask([s]{std::lock_guard inner(s->command_lock);if(s->endpoint)s->endpoint->Stop();});
}
bool PreemptForProduction(){
 std::shared_ptr<LabSession>s;{auto&r=All();std::lock_guard lock(r.mutex);if(r.sessions.empty())return true;s=r.sessions.begin()->second;}
 RevokeSession(s->id);
 // Only hardware-stop acknowledgement is waited for, never model/file disposal.
 const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
 while(!s->hardware_stopped.load()&&std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(std::chrono::milliseconds(1));
 return s->hardware_stopped.load();
}
bool ReleaseSession(uint64_t id){
 auto s=FindSession(id);if(!s)return false;
 {std::lock_guard lock(s->command_lock);if(s->endpoint||!s->hardware_stopped.load())return false;}
 s->state.Revoke();molly_denoise::ClearConstructionService(&s->service);
 auto&r=All();std::lock_guard lock(r.mutex);return r.sessions.erase(id)==1;
}
uint64_t CommandSession(uint64_t id,int op,int64_t a,int64_t b,int64_t c,int64_t d){return Post(FindSession(id),[=](LabEndpoint&e){return e.Execute(op,a,b,c,d);},op==2);}
uint64_t ConfigureSession(uint64_t id,std::span<const float>fields){
 auto s=FindSession(id);if(!s||fields.size()!=41||fields[0]!=1||!std::all_of(fields.begin(),fields.end(),[](float v){return std::isfinite(v);}))return 0;
 ScopeConfiguration cfg;
 for(size_t i=0;i<2;++i){const auto f=fields.subspan(1+i*8,8);if((f[0]!=0&&f[0]!=1)||(f[1]!=0&&f[1]!=1)||(f[3]!=0&&f[3]!=1))return 0;
  auto&d=i?cfg.sent:cfg.received;d.enabled=f[0]!=0;d.model=static_cast<molly_denoise::Model>(static_cast<uint32_t>(f[1]));d.parameters={1,f[2],static_cast<uint32_t>(f[3]),f[4],f[5],f[6],f[7],0};if(!d.Valid())return 0;
 }
 std::array<float,24>effect;std::copy_n(fields.begin()+17,24,effect.begin());cfg.effects=molly_audio::Settings::Unpack(effect);if(cfg.effects.Pack()!=effect)return 0;
 return Post(s,[s,cfg](LabEndpoint&e){s->requested=cfg;e.ApplyEffective();return true;});
}
uint64_t LoadSession(uint64_t id,int direction,uint32_t rate,uint32_t channels,std::vector<int16_t>pcm){
 if(direction<0||direction>1)return 0;Clip checked;if(!checked.Assign(rate,channels,std::move(pcm)))return 0;
 // Clip's lifetime and move remain entirely on control threads.
 auto s=FindSession(id);if(!s||s->state.Revoked()||!s->hardware_stopped.load())return 0;
 auto bytes=std::make_shared<Clip>(std::move(checked));
 return Post(s,[s,direction,bytes](LabEndpoint&){if(!s->hardware_stopped.load())return false;s->clips[direction]=std::move(*bytes);return true;});
}
bool ReadSession(uint64_t id,std::span<float>out){
 auto s=FindSession(id);if(!s||out.size()!=128)return false;std::fill(out.begin(),out.end(),0.f);
 out[0]=1;out[1]=s->state.Revoked()?4.f:static_cast<float>(s->status.load());out[2]=static_cast<float>(s->error.load());out[3]=static_cast<float>(s->ack.load());out[4]=s->hardware_stopped.load();out[5]=static_cast<float>(s->state.Epoch());out[6]=static_cast<float>(s->state.Segment());out[7]=static_cast<float>(s->mode.load());out[8]=static_cast<float>(s->position_ms.load());out[9]=s->finished.load();out[10]=s->warming.load();out[11]=static_cast<float>(s->state.Dropped());out[12]=s->state.RecordingIncomplete();out[13]=s->state.Muted();
 StatusFields(*s,0,out.subspan(16,16));StatusFields(*s,1,out.subspan(32,16));
 for(size_t i=0;i<5;++i){const auto level=s->state.Levels(static_cast<Tap>(i));const auto o=48+i*12;
  out[o]=level.epoch!=0;out[o+1]=static_cast<float>(level.rate);out[o+2]=static_cast<float>(level.channels);out[o+3]=static_cast<float>(level.blocks);out[o+4]=level.peak;out[o+5]=level.rms;out[o+6]=static_cast<float>(level.overs);out[o+7]=static_cast<float>(level.saturation);out[o+8]=static_cast<float>(level.samples);out[o+9]=static_cast<float>(level.source_start);
 }
 for(size_t i=0;i<6;++i)out[108+i]=s->scope.effects.meter_values[i].load();
 out[114]=static_cast<float>(s->scope.effects.frame_counter.load());out[115]=static_cast<float>(s->scope.Revision());
 out[116]=-1;out[117]=-1; // Device-delay estimates unavailable until backend timestamp proof.
 return true;
}
int DrainSession(uint64_t id,int tap,std::span<int64_t>meta,std::span<float>pcm){
 auto s=FindSession(id);if(!s||tap<0||tap>4||meta.size()!=16||pcm.size()<kTapSamples)return -1;
 TapPacket packet;if(!s->state.Drain(static_cast<Tap>(tap),packet))return 0;
 const auto count=packet.Count();if(!count||s->state.Revoked())return 0;
 const std::array<int64_t,16>values{1,static_cast<int64_t>(packet.epoch),static_cast<int64_t>(packet.segment),static_cast<int64_t>(packet.configuration),tap,packet.rate,packet.channels,packet.frames,packet.source_start,packet.time_ns,packet.flags,static_cast<int64_t>(s->state.Dropped()),0,0,0,0};
 std::copy(values.begin(),values.end(),meta.begin());std::copy_n(packet.pcm.begin(),count,pcm.begin());return static_cast<int>(count);
}
}
