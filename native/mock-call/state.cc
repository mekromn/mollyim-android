/* SPDX-License-Identifier: AGPL-3.0-only */
#include "state.h"
#include <cmath>
namespace molly_mock {
LabState::LabState(){for(auto& q:queues_)q=std::make_unique<TapQueue>();}
bool LabState::Start(uint64_t segment,uint32_t mask)noexcept {
 if(Revoked()||!segment||mask>31||running_.load())return false;
 segment_.store(segment);muted_.store(false);incomplete_.store(false);
 const auto e=epoch_.fetch_add(1)+1;drain_epoch_.store(e);mask_.store(mask);running_.store(true);return !Revoked();
}
bool LabState::Observe(const TapPacket& p)noexcept {
 const auto tap=static_cast<size_t>(p.tap);const auto count=p.Count();
 if(!count||!Allowed(p.epoch)||p.segment!=segment_.load()||(Muted()&&p.tap!=Tap::PlaybackReference&&p.tap!=Tap::ReceivedResult))return false;
 TapLevels value;levels_[tap].Read(value);if(value.epoch!=p.epoch)value={};
 value.epoch=p.epoch;value.rate=p.rate;value.channels=p.channels;value.source_start=p.source_start;value.time_ns=p.time_ns;
 float peak=0;double energy=0;
 for(size_t i=0;i<count;++i){const float sample=p.pcm[i];if(!std::isfinite(sample)){incomplete_.store(true);mask_.store(0);return false;}
  peak=std::max(peak,std::abs(sample));energy+=static_cast<double>(sample)*sample;
  if(std::abs(sample)>1.f)++value.overs;
  if((p.flags&1)&& (sample<=-1.f||sample>=32767.f/32768))++value.saturation;
 }
 value.peak=peak;value.rms=static_cast<float>(std::sqrt(energy/count));++value.blocks;value.samples+=count;levels_[tap].Publish(value);
 if(!(mask_.load()&(1u<<tap)))return true;
 if(!Allowed(p.epoch))return false;
 if(!queues_[tap]->Push(p)){dropped_.fetch_add(1);incomplete_.store(true);mask_.store(0);return false;}
 return true;
}
bool LabState::Drain(Tap tap,TapPacket& out)noexcept {
 const auto i=static_cast<size_t>(tap);if(i>=queues_.size())return false;
 for(unsigned n=0;n<32;++n){if(!queues_[i]->Pop(out))return false;
  if(!Revoked()&&out.epoch==drain_epoch_.load()&&out.segment==segment_.load())return true;
 }
 out={};return false;
}
TapLevels LabState::Levels(Tap tap)const noexcept {
 const auto i=static_cast<size_t>(tap);TapLevels out;if(i>=levels_.size())return out;
 levels_[i].Read(out);if(Revoked()||out.epoch!=Epoch())return {};return out;
}
}
