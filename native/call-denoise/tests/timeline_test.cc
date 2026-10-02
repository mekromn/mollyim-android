/* SPDX-License-Identifier: AGPL-3.0-only */
#include "timeline.h"
#include "spsc.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <thread>
#include <vector>
using namespace molly_denoise;
static DfMeta Meta(bool ll) { return {1,48000,480,960,ll?0u:2u,5,ll?480u:1440u}; }
static OriginalPacket Packet(int rate,int channels,int64_t start,uint64_t seq=0,uint64_t generation=1) {
  OriginalPacket p{};
  p.meta={7,generation,start,seq,1000000000LL+start*1000000000LL/rate,0,0,static_cast<uint32_t>(rate),static_cast<uint16_t>(channels),static_cast<uint16_t>(rate/100)};
  return p;
}
static void delay_table() {
  for (int r:{8000,16000,32000,48000}) for(int ch:{1,2}) for(bool ll:{false,true}) {
    auto p=DelayPlan::For(r,ch,Meta(ll)); assert(p);
    const uint32_t q=r/100;
    assert(p->total_samples==(ll?(r==48000?3:4):(r==48000?5:6))*q);
    assert(p->inner_samples==p->total_samples-2*q);
    assert(p->model_samples_48k==(ll?480:1440));
    assert(std::abs(p->converter_samples-(r==48000?0:16.+std::ceil(16.*r/48000.)))<1e-9);
  }
  assert(!DelayPlan::For(44100,1,Meta(false)));assert(!DelayPlan::For(48000,0,Meta(false)));
  assert(!DelayPlan::For(48000,3,Meta(false)));auto m=Meta(false);m.hop=240;assert(!DelayPlan::For(48000,1,m));
  m=Meta(false);m.lookahead=100;assert(!DelayPlan::For(48000,1,m));
  static_assert(sizeof(PacketMeta)==64);static_assert(sizeof(OriginalPacket)==1984);static_assert(sizeof(FilteredPacket)==3904);
  static_assert(8*sizeof(OriginalPacket)+16*sizeof(FilteredPacket)+16*sizeof(OriginalPacket)==110080);
}
static void ring_full_wrap_concurrent() {
  Spsc<uint64_t,8> ring(std::numeric_limits<uint64_t>::max()-3);
  for(uint64_t i=0;i<8;++i) { assert(ring.Push(i)); }
  assert(!ring.Push(9));
  uint64_t x=42;for(uint64_t i=0;i<8;++i){assert(ring.Pop(x));assert(x==i);}assert(!ring.Pop(x));
  Spsc<uint64_t,16> concurrent(std::numeric_limits<uint64_t>::max()-3);
  std::thread producer([&]{for(uint64_t i=1;i<=100000;++i)while(!concurrent.Push(i)) std::this_thread::yield();});
  for(uint64_t i=1;i<=100000;++i){while(!concurrent.Pop(x))std::this_thread::yield();assert(x==i);}producer.join();
}
static void source_interval_and_generation() {
  DryHistory history;
  for(int i=0;i<20;++i){auto p=Packet(48000,1,i*480,i);p.pcm[0]=static_cast<int16_t>(i+1);assert(history.Store(p));}
  auto p=Packet(48000,1,19*480,19);const auto plan=*DelayPlan::For(48000,1,Meta(false));
  auto expected=plan.Represented(p.meta,plan.total_samples);assert(expected);assert(expected->source_start==14*480);
  assert(expected->sequence==14);assert(expected->capture_time_ns==1140000000);
  OriginalPacket dry{};assert(history.Find(*expected,dry));assert(dry.pcm[0]==15);
  auto bad=*expected;bad.generation=2;assert(!history.Find(bad,dry));bad=*expected;bad.owner=8;assert(!history.Find(bad,dry));
  bad=*expected;bad.source_start=0;assert(!history.Find(bad,dry));
  history.Clear();assert(!history.Find(*expected,dry));
  auto near=Packet(48000,1,0);near.meta.source_start=std::numeric_limits<int64_t>::min();assert(!plan.Represented(near.meta,plan.total_samples));
  near=Packet(48000,1,0);near.meta.capture_time_ns=-1;auto early=plan.Represented(near.meta,plan.total_samples);assert(early&&early->capture_time_ns==-1&&early->source_start==-2400);
  near.meta.frames=65535;assert(!history.Store(near));
}
class DelayHop final:public HopProcessor {
 public: explicit DelayHop(size_t d):delay_(d){}
  bool Process(std::span<const float> in,std::span<float> out,float& snr) noexcept override {
    assert(in.size()==480&&out.size()==480);for(size_t i=0;i<480;++i){out[i]=buffer_[cursor_];buffer_[cursor_]=in[i];cursor_=(cursor_+1)%delay_;}snr=20;return true;
  }
 private:std::array<float,1440> buffer_{};size_t cursor_=0,delay_;
};
static void impulses_mixtures_and_channels() {
  constexpr int blocks=70;
  for(int rate:{8000,16000,32000,48000})for(bool ll:{false,true}) {
    const auto plan=*DelayPlan::For(rate,2,Meta(ll));const int q=rate/100,impulse=20*q+q/2;
    DelayHop left(plan.model_samples_48k),right(plan.model_samples_48k);
    std::array<HopProcessor*,2> engines{&left,&right};RateAdapter adapter(plan,engines);
    assert(adapter.resampler_count()==(rate==48000?0:4));
    std::vector<float> rendered(blocks*q*2);
    for(int b=0;b<blocks;++b){auto p=Packet(rate,2,b*q,b);if(impulse>=b*q&&impulse<(b+1)*q)p.pcm[(impulse-b*q)*2]=8192;FilteredPacket out{};
      assert(adapter.Process(p,out));assert(out.meta.source_start==static_cast<int64_t>(b)*q-plan.inner_samples);
      for(int i=0;i<q*2;++i)rendered[b*q*2+i]=out.pcm[i];
    }
    size_t peak=0;for(int i=0;i<blocks*q;++i){if(std::abs(rendered[2*i])>std::abs(rendered[2*peak]))peak=i;assert(std::abs(rendered[2*i+1])<1e-12);}
    assert(std::abs(static_cast<int64_t>(peak)-impulse-plan.inner_samples)<=1);
    for(double hz:{200.,700.,0.2*rate}){
      DelayHop l(plan.model_samples_48k),r(plan.model_samples_48k);RateAdapter tones(plan,{&l,&r});
      double dry2=0,wet2=0,mix2=0,cross=0;
      for(int b=0;b<blocks;++b){auto p=Packet(rate,2,b*q,b);for(int i=0;i<q;++i)p.pcm[2*i]=static_cast<int16_t>(6000*std::sin(2*M_PI*hz*(b*q+i)/rate));
        FilteredPacket out{};assert(tones.Process(p,out));if(b<20)continue;
        for(int i=0;i<q;++i){double dry=6000./32768*std::sin(2*M_PI*hz*(out.meta.source_start+i)/rate),wet=out.pcm[2*i];dry2+=dry*dry;wet2+=wet*wet;mix2+=std::pow((dry+wet)/2,2);cross+=dry*wet;}
      }
      std::cerr<<"tone rate="<<rate<<" LL="<<ll<<" Hz="<<hz<<" correlation="<<cross/std::sqrt(dry2*wet2)<<" mixPower="<<mix2/dry2<<"\n";
      assert(cross/std::sqrt(dry2*wet2)>0.995);assert(mix2/dry2>0.90&&mix2/dry2<1.03);
    }
    std::cout<<"PASS real WebRTC resamplers, impulse/mixture/stereo "<<rate<<" "<<(ll?"LL":"Standard")<<"\n";
  }
}
static void invalid_plan_is_rejected() {
  auto bad=*DelayPlan::For(48000,1,Meta(false));bad.frames=0;
  auto packet=Packet(48000,1,0);assert(!bad.Represented(packet.meta,480));
  bad=*DelayPlan::For(48000,1,Meta(false));bad.channels=65535;
  RateAdapter adapter(bad,{nullptr,nullptr});FilteredPacket out{};assert(!adapter.Process(packet,out));
}
class FaultHop final:public HopProcessor {
 public:
  bool fail=false,nonfinite=false;
  int calls=0;
  bool Process(std::span<const float> in,std::span<float> out,float& snr) noexcept override {
    ++calls;std::copy(in.begin(),in.end(),out.begin());snr=20;
    if(nonfinite)out[0]=std::numeric_limits<float>::quiet_NaN();
    return !fail;
  }
};
static void failed_channel_poisons_adapter() {
  for(bool nonfinite:{false,true}) {
    FaultHop left,right;right.fail=!nonfinite;right.nonfinite=nonfinite;
    const auto plan=*DelayPlan::For(48000,2,Meta(false));RateAdapter adapter(plan,{&left,&right});
    auto packet=Packet(48000,2,0);packet.pcm.fill(1234);FilteredPacket output{};
    assert(!adapter.Process(packet,output));
    assert(!output.meta.Valid());for(float sample:output.pcm)assert(sample==0);
    right.fail=false;right.nonfinite=false;const int calls=left.calls+right.calls;
    assert(!adapter.Process(packet,output));assert(left.calls+right.calls==calls);
  }
  FaultHop left,right;const auto plan=*DelayPlan::For(16000,1,Meta(true));RateAdapter adapter(plan,{&left,&right});
  auto packet=Packet(16000,1,0);FilteredPacket output{};assert(adapter.Process(packet,output));
  packet=Packet(16000,1,160,1,2);assert(!adapter.Process(packet,output));
  packet=Packet(16000,1,160,1,1);assert(!adapter.Process(packet,output));
}
int main(){failed_channel_poisons_adapter();invalid_plan_is_rejected();delay_table();std::cout<<"PASS delay table / packet capacities\n";ring_full_wrap_concurrent();std::cout<<"PASS full/wrap/concurrent SPSC\n";source_interval_and_generation();std::cout<<"PASS matched dry / epoch / timestamp bounds\n";impulses_mixtures_and_channels();}
