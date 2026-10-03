/* SPDX-License-Identifier: AGPL-3.0-only */
#include "worker.h"
#include "spsc.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <semaphore>
#include <thread>
namespace molly_denoise {
namespace {
uint64_t NowUs() noexcept {return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());}
struct Request {
  uint64_t epoch=0,invalidation=0;ControlSnapshot settings;PacketMeta stream;
  bool active=false;
};
struct Cursor {uint64_t epoch=0;int64_t source=0;};
struct Input {uint64_t epoch=0;OriginalPacket packet;};
struct Output {uint64_t epoch=0;FilteredPacket packet;};
struct WorkerReport {
  uint64_t epoch=0,count=0,updated_us=0;bool ready=false,failed=false,started=false;
  int64_t first_source=0;float snr=0,mean_us=0,p95_us=0;
};
struct Timings {
  std::array<float,500> values{},sorted{};size_t cursor=0,count=0;double sum=0;
  void Add(float v) noexcept {sum-=values[cursor];values[cursor]=v;sum+=v;cursor=(cursor+1)%500;count=std::min(count+1,size_t{500});}
  float Mean() const noexcept{return count?static_cast<float>(sum/count):0;}
  float P95() noexcept {if(!count)return 0;std::copy_n(values.begin(),count,sorted.begin());size_t at=(count*95+99)/100-1;auto window=std::span(sorted).first(count);std::nth_element(window.begin(),window.subspan(at).begin(),window.end());return sorted[at];}
};
DfMeta ModelMeta(Model model) noexcept {const uint32_t look=model==Model::LowLatency?0:2;return {1,48000,480,960,look,5,480+480*look};}
bool SameStream(const PacketMeta& a,const PacketMeta& b) noexcept {return a.owner==b.owner&&a.generation==b.generation&&a.rate==b.rate&&a.channels==b.channels;}
}
struct DirectionProcessor::Impl {
  DenoiseControl& control;Direction direction;EngineFactory& factory;
  AtomicSnapshot<Request> request_bus;
  AtomicSnapshot<Cursor> cursor_bus;
  AtomicSnapshot<WorkerReport> report_bus;
  AtomicSnapshot<DenoiseStatus> status_bus;
  Spsc<Input,8> input;
  Spsc<Output,16> output;
  std::atomic<uint64_t> cancelled_epoch{0},invalidation{1};
  std::atomic<bool> stop{false},signaled{false};
  std::binary_semaphore signal{0};
  std::thread thread;
  bool threaded;

  // Audio-callback-owned fields.
  ControlSnapshot config;
  Request requested;
  WorkerReport observed;
  DenoiseStatus status;
  DryHistory dry;
  std::array<Output,16> future{};
  std::optional<PacketMeta> previous;
  std::optional<DelayPlan> plan;
  OverloadWindow misses;
  bool overload_latched=false;
  uint64_t epoch=0,callback_count=0,callback_invalidation=0,active_owner=0;
  int64_t floor_source=0;
  unsigned off_phase=0;
  float wet_gain=0;

  // Worker-owned fields.
  Request running;
  WorkerReport report;
  Timings timings;
  std::array<std::unique_ptr<StreamingEngine>,2> engines;
  std::unique_ptr<RateAdapter> adapter;
  std::optional<PacketMeta> last_input;
  int64_t reset_source=0;

  Impl(DenoiseControl& c,Direction d,EngineFactory& f,bool threaded):control(c),direction(d),factory(f),threaded(threaded){
    if(threaded)thread=std::thread([this]{while(true){signal.acquire();signaled.store(false);if(stop.load())break;Pump();}Release();});
  }
  ~Impl(){stop.store(true);Kick();if(thread.joinable())thread.join();else Release();dry.Clear();future.fill(Output{});}
  void Kick() noexcept {if(threaded&&!signaled.exchange(true))signal.release();}
  void Release(){adapter.reset();for(auto& e:engines)e.reset();last_input.reset();}
  bool IsCurrent(const Request& req) const noexcept {
    Request now;if(!request_bus.Read(now))return false;
    return !stop.load()&&req.invalidation==invalidation.load()&&req.epoch==now.epoch&&now.active&&cancelled_epoch.load()!=req.epoch;
  }
  void PublishReport(){report.updated_us=NowUs();report.mean_us=timings.Mean();report.p95_us=timings.P95();report_bus.Publish(report);}
  void DrainInputs(){Input discarded;for(size_t i=0;i<8&&input.Pop(discarded);++i)discarded={};}
  void Fail(){report.failed=true;report.ready=false;Release();DrainInputs();PublishReport();}
  bool Reframe(const DelayPlan& p,int64_t source){
    adapter.reset();for(size_t c=0;c<p.channels;++c)if(!engines[c]->Reset())return false;
    adapter=std::make_unique<RateAdapter>(p,std::array<HopProcessor*,2>{engines[0].get(),engines[1].get()});
    reset_source=source;last_input.reset();return true;
  }
  void Pump(){
    Request req;if(!request_bus.Read(req))return;
    if(req.epoch!=running.epoch){Release();running=req;report={};report.epoch=req.epoch;timings={};PublishReport();}
    if(!req.active||req.invalidation!=invalidation.load()||cancelled_epoch.load()==req.epoch||stop.load()){
      Release();DrainInputs();report.ready=false;PublishReport();return;
    }
    if(report.failed){DrainInputs();return;}
    const auto delay=DelayPlan::For(req.stream.rate,req.stream.channels,ModelMeta(req.settings.config.model));
    if(!delay){Fail();return;}
    if(!engines[0]){
      for(size_t c=0;c<delay->channels;++c){
        if(!IsCurrent(req)){Release();return;}
        engines[c]=factory.Create(req.settings.config.model,static_cast<uint32_t>(c),req.settings.config.parameters);
        if(!engines[c]){Fail();return;}
        const auto actual=DelayPlan::For(req.stream.rate,req.stream.channels,engines[c]->Metadata());
        if(!actual||actual->total_samples!=delay->total_samples){Fail();return;}
      }
      if(!IsCurrent(req)){Release();return;}
      report.ready=true;PublishReport();
    }
    if(req.settings.revision!=running.settings.revision){
      for(size_t c=0;c<delay->channels;++c)if(!engines[c]->Configure(req.settings.config.parameters)){Fail();return;}
      running.settings=req.settings;
    }
    Input item;
    for(size_t n=0;n<8&&input.Pop(item);++n){
      if(item.epoch!=req.epoch||!SameStream(item.packet.meta,req.stream)){item={};continue;}
      if(!IsCurrent(req)){Release();item={};return;}
      Cursor current;if(!cursor_bus.Read(current)||current.epoch!=req.epoch){item={};continue;}
      // The callback for source+2Q has already selected its output. Computing
      // this item now would create a backlog, not rescue that deadline.
      if(item.packet.meta.source_start<=current.source-int64_t(2*delay->frames)){item={};continue;}
      const bool gap=last_input&&(last_input->source_start>std::numeric_limits<int64_t>::max()-delay->frames||item.packet.meta.source_start!=last_input->source_start+delay->frames);
      if(!adapter||gap){
        if(!Reframe(*delay,item.packet.meta.source_start)){Fail();return;}
      }
      if(!report.started){report.started=true;report.first_source=item.packet.meta.source_start;}
      Output result;result.epoch=req.epoch;const auto begin=NowUs();
      const bool ok=adapter->Process(item.packet,result.packet);
      const auto duration=NowUs()-begin;
      last_input=item.packet.meta;item={};
      if(!ok){Fail();return;}
      timings.Add(static_cast<float>(duration));++report.count;report.snr=adapter->last_snr();
      if(!IsCurrent(req)){Release();return;}
      Cursor latest;if(!cursor_bus.Read(latest)||latest.epoch!=req.epoch)continue;
      if(result.packet.meta.source_start>=reset_source&&result.packet.meta.source_start>latest.source-int64_t(delay->total_samples))output.Push(result);
      result={};
      // Statistics are published at 10 Hz, plus readiness/failure transitions.
      if(report.count==1||report.count%10==0)PublishReport();
    }
  }
  void ClearAudio() noexcept {dry.Clear();future.fill(Output{});wet_gain=0;}
  void Drain(const PacketMeta& expected) noexcept {
    Output result;
    for(size_t i=0;i<16&&output.Pop(result);++i){
      if(result.epoch==epoch&&SameStream(result.packet.meta,expected)&&result.packet.meta.source_start>=expected.source_start)future[result.packet.meta.sequence&15]=result;
      result={};
    }
  }
  bool FindWet(const PacketMeta& expected,FilteredPacket& wet) noexcept {
    for(auto& slot:future){
      if(slot.epoch==epoch&&slot.packet.meta.SameInterval(expected)){wet=slot.packet;slot={};return true;}
      if(slot.epoch!=epoch||slot.packet.meta.source_start<expected.source_start)slot={};
    }
    return false;
  }
  static void Original(const OriginalPacket& in,std::span<float> out) noexcept {
    for(size_t i=0;i<in.meta.samples();++i)out[i]=in.pcm[i]/32768.f;
  }
  void Publish(const OriginalPacket& in,std::span<float> out,const ProcessResult& result) noexcept {
    const bool changed=status.state!=result.state;status.state=result.state;status.rate=in.meta.rate;status.channels=in.meta.channels;
    status.delay_samples=(result.kind==OutputKind::Direct||!plan)?0:plan->total_samples;
    status.input_peak=0;status.output_peak=0;for(size_t i=0;i<in.meta.samples();++i){status.input_peak=std::max(status.input_peak,std::abs(in.pcm[i]/32768.f));status.output_peak=std::max(status.output_peak,std::abs(out[i]));}
    status.levels_valid=in.meta.Valid();status.misses=misses.misses();
    // Keep timing evidence valid while aligned dry fallback is protecting the
    // call. Overload is an output choice, not a reason to hide worker timing.
    status.inference_valid=observed.epoch==epoch&&observed.count&&!observed.failed;
    status.snr=status.inference_valid?observed.snr:0;status.mean_us=status.inference_valid?observed.mean_us:0;status.p95_us=status.inference_valid?observed.p95_us:0;
    status.processed=observed.epoch==epoch?observed.count:0;
    if(changed||++callback_count%10==0){status.updated_us=NowUs();status_bus.Publish(status);}
  }
  ProcessResult Process(const OriginalPacket& in,std::span<float> out) noexcept {
    std::fill_n(out.begin(),std::min(out.size(),kMaxSamples),0.f);
    ProcessResult result;result.source=in.meta;
    if(!in.meta.Valid()||in.meta.source_start<0||out.size()<in.meta.samples()){
      result.kind=OutputKind::Direct;result.state=EffectiveState::Unsupported;
      cancelled_epoch.store(epoch);Kick();ClearAudio();previous.reset();status.state=result.state;status.levels_valid=false;status.inference_valid=false;status.updated_us=NowUs();status_bus.Publish(status);return result;
    }
    ControlSnapshot next=config;control.Read(direction,next);
    const uint64_t cancellation=invalidation.load();
    const bool stream_change=cancellation!=callback_invalidation||!previous||!SameStream(*previous,in.meta)||previous->source_start>std::numeric_limits<int64_t>::max()-in.meta.frames||in.meta.source_start!=previous->source_start+in.meta.frames;
    const bool new_call=active_owner!=in.meta.owner;
    active_owner=in.meta.owner;
    const bool enabled_changed=next.config.enabled!=config.config.enabled;
    const bool model_changed=next.config.model!=config.config.model;
    const bool bypass_changed=next.bypassed!=config.bypassed||(next.config.parameters.attenuation_db==0)!=(config.config.parameters.attenuation_db==0);
    const bool retry=next.retry!=config.retry;
    const bool was_enabled=config.config.enabled;
    if(new_call||retry||model_changed||(enabled_changed&&next.config.enabled)||(bypass_changed&&!next.bypassed))overload_latched=false;
    const bool new_epoch=stream_change||enabled_changed||model_changed||bypass_changed||retry;
    if(new_epoch){
      ++epoch;if(epoch==0)++epoch;observed={};misses.Reset();
      if(stream_change||model_changed||(enabled_changed&&next.config.enabled)){
        ClearAudio();floor_source=in.meta.source_start;
      }
      if(was_enabled&&!next.config.enabled&&!stream_change)off_phase=2;
      if(next.config.enabled)off_phase=0;
      if(next.config.enabled||!plan)plan=DelayPlan::For(in.meta.rate,in.meta.channels,ModelMeta(next.config.model));
    }
    const bool active=next.config.enabled&&!next.bypassed&&next.config.parameters.attenuation_db>0;
    if(new_epoch||next.revision!=config.revision){
      requested={epoch,cancellation,next,in.meta,active};request_bus.Publish(requested);Kick();
    }
    config=next;callback_invalidation=cancellation;previous=in.meta;cursor_bus.Publish(Cursor{epoch,in.meta.source_start});
    if(!config.config.enabled&&off_phase==0){
      Original(in,out);result.kind=OutputKind::Direct;result.state=EffectiveState::Off;Publish(in,out,result);return result;
    }
    dry.Store(in);
    if(!plan){Original(in,out);result.state=EffectiveState::Unsupported;Publish(in,out,result);return result;}
    if(off_phase==1){
      Original(in,out);for(size_t i=0;i<in.meta.samples();++i)out[i]*=static_cast<float>(i/in.meta.channels+1)/in.meta.frames;
      off_phase=0;ClearAudio();result.kind=OutputKind::Transition;result.state=EffectiveState::Off;Publish(in,out,result);return result;
    }
    const auto represented=plan->Represented(in.meta,plan->total_samples);
    if(!represented){result.kind=OutputKind::Muted;result.state=EffectiveState::Unsupported;Publish(in,out,result);return result;}
    result.source=*represented;result.kind=OutputKind::DelayedDry;
    OriginalPacket original;const bool has_dry=represented->source_start>=floor_source&&dry.Find(*represented,original);
    if(has_dry){Original(original,out);result.source=original.meta;}
    if(off_phase==2){
      for(size_t i=0;i<in.meta.samples();++i)out[i]*=1.f-static_cast<float>(i/in.meta.channels+1)/in.meta.frames;
      off_phase=1;result.kind=OutputKind::Transition;result.state=EffectiveState::Off;Publish(in,out,result);return result;
    }
    report_bus.Read(observed);
    const bool failed=observed.epoch==epoch&&observed.failed;
    if(active&&!failed){input.Push(Input{epoch,in});Kick();}
    Drain(*represented);FilteredPacket wet;
    const bool has_wet=active&&!failed&&has_dry&&FindWet(*represented,wet);
    const bool eligible=active&&!failed&&has_dry&&observed.epoch==epoch&&observed.ready&&observed.started&&represented->source_start>=observed.first_source;
    // Overload never cancels inference. Keep the bounded worker running so it
    // can measure the real cost and catch back up while the callback safely
    // returns the time-aligned dry frame.
    overload_latched=misses.Observe(eligible,!has_wet);
    if(overload_latched)result.state=EffectiveState::Overloaded;
    else if(failed)result.state=EffectiveState::Unavailable;
    else if(config.bypassed)result.state=EffectiveState::ManualBypass;
    else if(config.config.parameters.attenuation_db==0)result.state=EffectiveState::ZeroSuppression;
    else result.state=has_wet?EffectiveState::Active:EffectiveState::Loading;
    if(has_wet&&!overload_latched){
      const float start=wet_gain;
      for(size_t i=0;i<in.meta.samples();++i){const float mix=start+(1-start)*static_cast<float>(i/in.meta.channels+1)/in.meta.frames;out[i]=out[i]*(1-mix)+wet.pcm[i]*mix;}
      wet_gain=1;result.kind=OutputKind::Wet;
    }else wet_gain=0;
    Publish(in,out,result);return result;
  }
};
DirectionProcessor::DirectionProcessor(DenoiseControl& c,Direction d,EngineFactory& f,bool threaded):impl_(std::make_unique<Impl>(c,d,f,threaded)){}
DirectionProcessor::~DirectionProcessor()=default;
ProcessResult DirectionProcessor::Process(const OriginalPacket& input,std::span<float> output) noexcept{return impl_->Process(input,output);}
DenoiseStatus DirectionProcessor::Status() const noexcept {DenoiseStatus result;impl_->status_bus.Read(result);if(NowUs()-result.updated_us>1500000){result.levels_valid=false;result.inference_valid=false;}return result;}
void DirectionProcessor::Invalidate() noexcept {impl_->invalidation.fetch_add(1);impl_->Kick();}
void DirectionProcessor::QuiescentInvalidate() noexcept {Invalidate();impl_->ClearAudio();impl_->previous.reset();}
void DirectionProcessor::PumpForTest(){if(!impl_->threaded)impl_->Pump();}
}
