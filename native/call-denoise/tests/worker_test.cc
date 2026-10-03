/* SPDX-License-Identifier: AGPL-3.0-only */
#include "worker.h"
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <new>
#include <thread>
#include <semaphore>
#include <chrono>
#include <limits>
using namespace molly_denoise;
static thread_local bool in_callback=false;
void* operator new(std::size_t n) { assert(!in_callback); if(void* p=std::malloc(n))return p; throw std::bad_alloc(); }
void operator delete(void* p) noexcept {std::free(p);}
void operator delete(void* p,std::size_t) noexcept {std::free(p);}
struct FakeFactory final:EngineFactory {
  struct Engine final:StreamingEngine {
    FakeFactory& f; DfMeta meta;std::array<float,1440> delay{};size_t pos=0;
    Engine(FakeFactory& f,uint32_t model):f(f),meta{1,48000,480,960,model==0?2u:0u,5,model==0?1440u:480u}{++f.live;f.max_live=std::max(f.max_live,f.live);}
    ~Engine() override{--f.live;}
    DfMeta Metadata() const noexcept override{return meta;}
    bool Configure(const DfConfig& c) noexcept override{f.last=c;++f.configured;return true;}
    bool Reset() noexcept override{delay.fill(0);pos=0;++f.resets;return true;}
    bool Process(std::span<const float> in,std::span<float> out,float& snr) noexcept override {
      ++f.processed;if(f.fail_process)return false;
      for(size_t i=0;i<in.size();++i){out[i]=delay[pos]*2;delay[pos]=in[i];pos=(pos+1)%meta.intrinsic_delay;}
      snr=7;return true;
    }
  };
  int created=0,processed=0,configured=0,resets=0,live=0,max_live=0;
  bool fail_load=false,fail_process=false;DfConfig last{};
  std::unique_ptr<StreamingEngine> Create(Model model,uint32_t,const DfConfig& config) override {
    ++created;if(fail_load)return nullptr;
    auto e=std::make_unique<Engine>(*this,static_cast<uint32_t>(model));e->Configure(config);return e;
  }
};
OriginalPacket Packet(int n,uint64_t gen=1,uint32_t rate=48000,uint16_t channels=1) {
  OriginalPacket p;p.meta={1,gen,int64_t(n)*int64_t(rate/100),uint64_t(n),1000000000LL+n*10000000LL,1000+n*10LL,1000+n*10LL,rate,channels,uint16_t(rate/100)};
  for(size_t i=0;i<p.meta.samples();++i)p.pcm[i]=int16_t(100+n);
  return p;
}
struct Harness {
  DenoiseControl control;FakeFactory factory;
  DirectionProcessor receive{control,Direction::Received,factory,false};
  std::array<float,kMaxSamples> output{};
  void Enable(Model model=Model::Standard){DenoiseConfig c;c.enabled=true;c.model=model;assert(control.Update(Direction::Received,c));}
  ProcessResult Step(int n,bool pump=true,uint64_t gen=1) {
    auto p=Packet(n,gen);in_callback=true;auto r=receive.Process(p,output);in_callback=false;
    if(pump)receive.PumpForTest();
    return r;
  }
};
void defaults_and_off_identity(){Harness h;for(int n=0;n<20;++n){auto r=h.Step(n);assert(r.kind==OutputKind::Direct);assert(h.output[0]==(100+n)/32768.f);}assert(h.factory.created==0);}
void late_output_uses_matched_dry(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);h.Step(12,false);h.Step(13,false);auto r=h.Step(14,false);assert(r.kind==OutputKind::DelayedDry);assert(r.source.source_start==9*480);assert(h.output[0]==109/32768.f);h.receive.PumpForTest();}
void three_misses_enter_safe_fallback_but_worker_recovers(){
  Harness h;h.Enable();
  for(int n=0;n<12;++n)h.Step(n);
  for(int n=12;n<19;++n)h.Step(n,false);
  const auto overloaded=h.receive.Status();
  assert(overloaded.state==EffectiveState::Overloaded);
  assert(overloaded.inference_valid);
  assert(overloaded.processed>0);
  const int count=h.factory.processed;
  bool recovered=false;
  for(int n=19;n<90;++n){
    auto result=h.Step(n);
    assert(h.factory.processed>=count);
    if(result.state==EffectiveState::Active){recovered=true;break;}
  }
  assert(h.factory.processed>count);
  assert(recovered);
}
void overload_window_ignores_ineligible_slots_and_recovers_with_hysteresis(){
  OverloadWindow w;
  for(int n=0;n<1000;++n)assert(!w.Observe(false,false));
  assert(!w.latched());
  assert(!w.Observe(true,true));
  assert(!w.Observe(true,true));
  assert(w.Observe(true,true));
  assert(w.latched());
  for(int n=0;n<19;++n)assert(w.Observe(true,false));
  assert(w.latched());
  assert(!w.Observe(true,false));
  assert(!w.latched());
}
void manual_bypass_suspends_inference_keeps_delay(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);h.control.Bypass(Direction::Received,true);h.Step(12);int count=h.factory.processed;for(int n=13;n<24;++n){auto r=h.Step(n);assert(r.kind==OutputKind::DelayedDry);assert(r.source.source_start==(n-5)*480);assert(h.output[0]==(100+n-5)/32768.f);}assert(count==h.factory.processed);h.control.Bypass(Direction::Received,false);for(int n=24;n<36;++n)h.Step(n);assert(h.factory.processed>count);}
void zero_is_aligned_dry(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);DenoiseConfig c;c.enabled=true;c.parameters.attenuation_db=0;h.control.Update(Direction::Received,c);h.Step(12);int count=h.factory.processed;for(int n=13;n<24;++n)h.Step(n);assert(h.factory.processed==count);assert(h.output[0]==118/32768.f);}
void settings_do_not_reset_stream(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);auto creations=h.factory.created;DenoiseConfig c;c.enabled=true;c.parameters.attenuation_db=12;h.control.Update(Direction::Received,c);for(int n=12;n<17;++n)h.Step(n);assert(h.factory.created==creations);assert(h.factory.last.attenuation_db==12);}
void receive_failure_does_not_stop_send(){DenoiseControl c;FakeFactory fr,fs;DirectionProcessor r(c,Direction::Received,fr,false),s(c,Direction::Sent,fs,false);DenoiseConfig e;e.enabled=true;c.Update(Direction::Received,e);c.Update(Direction::Sent,e);fr.fail_load=true;std::array<float,kMaxSamples> out{};for(int n=0;n<20;++n){r.Process(Packet(n),out);r.PumpForTest();s.Process(Packet(n),out);s.PumpForTest();}assert(r.Status().state==EffectiveState::Unavailable);assert(s.Status().state==EffectiveState::Active);assert(fr.created==1);assert(fs.processed>5);}
void model_switch_load_failure(){Harness h;h.Enable();for(int n=0;n<15;++n)h.Step(n);h.factory.fail_load=true;h.Enable(Model::LowLatency);for(int n=15;n<30;++n)h.Step(n);assert(h.factory.live==0);assert(h.factory.max_live<=1);assert(h.receive.Status().state==EffectiveState::Unavailable);assert(h.output[0]==126/32768.f);}
void rapid_switch_has_no_old_epoch(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);for(int n=12;n<24;++n){h.Enable(n%2?Model::Standard:Model::LowLatency);h.Step(n,n%3==0);}h.Enable(Model::LowLatency);for(int n=24;n<40;++n){auto r=h.Step(n);if(r.kind==OutputKind::Wet)assert(r.source.source_start>=24*480);}assert(h.factory.max_live<=1);}
void off_returns_direct_and_no_replay(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);h.control.Update(Direction::Received,DenoiseConfig{});h.Step(12);h.Step(13);for(int n=14;n<25;++n){auto r=h.Step(n);assert(r.kind==OutputKind::Direct);assert(h.output[0]==(100+n)/32768.f);}assert(h.factory.live==0);}
void config_atomic_and_direction_isolation(){DenoiseControl c;std::atomic<bool> done=false;std::thread writer([&]{for(int n=0;n<20000;++n){DenoiseConfig x;x.enabled=true;x.parameters.attenuation_db=n%2?12:30;x.parameters.beta=n%2?.01f:.02f;assert(c.Update(Direction::Received,x));}done=true;});ControlSnapshot v;while(!done.load()){if(c.Read(Direction::Received,v)&&v.revision>0){assert((v.config.parameters.attenuation_db==12&&v.config.parameters.beta==.01f)||(v.config.parameters.attenuation_db==30&&v.config.parameters.beta==.02f));}}writer.join();ControlSnapshot s;assert(c.Read(Direction::Sent,s)&&!s.config.enabled&&s.revision==0);}
void generation_invalidates_audio(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);for(int n=12;n<17;++n){auto r=h.Step(n,true,2);assert(r.source.generation==2);for(float v:h.output)assert(v==0);}for(int n=17;n<30;++n)h.Step(n,true,2);}
void stress_no_callback_allocation(){Harness h;h.Enable();for(int n=0;n<100000;++n){if(n%1000==400)h.control.Bypass(Direction::Received,true);if(n%1000==500)h.control.Bypass(Direction::Received,false);h.Step(n);}assert(h.factory.live<=1);}

struct BlockingFactory final:EngineFactory {
  std::atomic<int> created{0},live{0},processed{0},destroyed{0};
  std::binary_semaphore loading{0},unblock{0};
  struct E final:StreamingEngine {
    BlockingFactory& f;explicit E(BlockingFactory& f):f(f){++f.live;}~E()override{--f.live;++f.destroyed;}
    DfMeta Metadata()const noexcept override{return {1,48000,480,960,2,5,1440};}
    bool Configure(const DfConfig&)noexcept override{return true;}
    bool Reset()noexcept override{return true;}
    bool Process(std::span<const float>,std::span<float> out,float& snr)noexcept override{++f.processed;std::fill(out.begin(),out.end(),.25f);snr=1;return true;}
  };
  std::unique_ptr<StreamingEngine> Create(Model,uint32_t,const DfConfig&)override{
    if(created.fetch_add(1)==0){loading.release();unblock.acquire();}
    return std::make_unique<E>(*this);
  }
};
template<class F> bool Wait(F test){for(int n=0;n<3000;++n){if(test())return true;std::this_thread::sleep_for(std::chrono::milliseconds(1));}return false;}
void invalidate_during_actual_load(){
  DenoiseControl c;BlockingFactory f;DirectionProcessor worker(c,Direction::Received,f,true);
  DenoiseConfig e;e.enabled=true;c.Update(Direction::Received,e);std::array<float,kMaxSamples> out{};
  worker.Process(Packet(0),out);assert(f.loading.try_acquire_for(std::chrono::seconds(3)));
  for(int n=1;n<100;++n){in_callback=true;worker.Process(Packet(n),out);in_callback=false;}
  worker.Invalidate();f.unblock.release();assert(Wait([&]{return f.destroyed.load()>0;}));
  assert(f.processed.load()==0);
  // A new producer epoch may resume; old load/completion cannot turn it on.
  auto r=worker.Process(Packet(100,2),out);assert(r.source.generation==2);
  for(float v:out)assert(v==0);
}
void repeated_invalidate_without_frames(){
  DenoiseControl c;BlockingFactory f;f.unblock.release();DirectionProcessor worker(c,Direction::Received,f,true);
  DenoiseConfig e;e.enabled=true;c.Update(Direction::Received,e);std::array<float,kMaxSamples> out{};
  worker.Process(Packet(0),out);assert(Wait([&]{return f.processed.load()>0;}));
  worker.Invalidate();assert(Wait([&]{return f.live.load()==0;}));
  for(int i=0;i<10000;++i)worker.Invalidate();
}
void invalid_native_config_is_rejected(){DenoiseControl c;DenoiseConfig e;e.enabled=true;e.parameters.beta=std::numeric_limits<float>::quiet_NaN();assert(!c.Update(Direction::Sent,e));e.parameters.beta=.02;e.parameters.df_snr=-20;assert(!c.Update(Direction::Sent,e));ControlSnapshot s;assert(c.Read(Direction::Sent,s)&&!s.config.enabled);}
void overload_survives_unsupported_format(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);for(int n=12;n<20;++n)h.Step(n,false);assert(h.receive.Status().state==EffectiveState::Overloaded);auto invalid=Packet(20);invalid.meta.channels=3;h.receive.Process(invalid,h.output);h.Step(21);assert(h.receive.Status().state==EffectiveState::Overloaded);}
void processing_failure_keeps_aligned_ordinary_audio(){Harness h;h.Enable();for(int n=0;n<12;++n)h.Step(n);h.factory.fail_process=true;h.Step(12);for(int n=13;n<25;++n){auto r=h.Step(n);assert(r.state==EffectiveState::Unavailable);assert(h.output[0]==(100+n-5)/32768.f);}assert(h.factory.live==0);}
int main(){std::cout.setf(std::ios::unitbuf);
#define RUN(t) t();std::cout<<#t<<" PASS\n";
RUN(defaults_and_off_identity);RUN(late_output_uses_matched_dry);RUN(three_misses_enter_safe_fallback_but_worker_recovers);RUN(overload_window_ignores_ineligible_slots_and_recovers_with_hysteresis);RUN(manual_bypass_suspends_inference_keeps_delay);RUN(zero_is_aligned_dry);RUN(settings_do_not_reset_stream);RUN(receive_failure_does_not_stop_send);RUN(model_switch_load_failure);RUN(rapid_switch_has_no_old_epoch);RUN(off_returns_direct_and_no_replay);RUN(config_atomic_and_direction_isolation);RUN(generation_invalidates_audio);RUN(stress_no_callback_allocation);RUN(invalidate_during_actual_load);RUN(repeated_invalidate_without_frames);RUN(invalid_native_config_is_rejected);RUN(overload_survives_unsupported_format);RUN(processing_failure_keeps_aligned_ordinary_audio);
}
