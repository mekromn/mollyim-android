/* SPDX-License-Identifier: AGPL-3.0-only */
#include "control.h"
#include <cmath>
namespace molly_denoise {
namespace {bool ValidDirection(Direction d){return d==Direction::Received||d==Direction::Sent;}}
bool DenoiseConfig::Valid() const noexcept {
  const auto& p=parameters;
  return (model==Model::Standard||model==Model::LowLatency)&&p.abi_version==1&&p.reserved==0
      &&p.post_filter_enabled<=1&&std::isfinite(p.attenuation_db)&&p.attenuation_db>=0&&p.attenuation_db<=100
      &&std::isfinite(p.beta)&&p.beta>=0&&p.beta<=.05f
      &&std::isfinite(p.min_snr)&&std::isfinite(p.erb_snr)&&std::isfinite(p.df_snr)
      &&p.min_snr>=-30&&p.min_snr<=p.df_snr&&p.df_snr<=p.erb_snr&&p.erb_snr<=60;
}
bool DenoiseControl::Update(Direction d,const DenoiseConfig& config) noexcept {
  if(!ValidDirection(d)||!config.Valid())return false;
  std::lock_guard lock(writer_);auto& c=current_[static_cast<size_t>(d)];c.config=config;++c.revision;
  published_.Publish(current_);return true;
}
bool DenoiseControl::UpdateBoth(const DenoiseConfig& received,const DenoiseConfig& sent) noexcept {
  if(!received.Valid()||!sent.Valid())return false;
  std::lock_guard lock(writer_);
  const uint64_t revision=std::max(current_[0].revision,current_[1].revision)+1;
  if(!revision)return false;
  current_[0].config=received;current_[1].config=sent;
  current_[0].revision=revision;current_[1].revision=revision;
  published_.Publish(current_);return true;
}
void DenoiseControl::Bypass(Direction d,bool bypass) noexcept {
  if(!ValidDirection(d))return;
  std::lock_guard lock(writer_);auto& c=current_[static_cast<size_t>(d)];
  if(c.bypassed!=bypass){c.bypassed=bypass;++c.revision;published_.Publish(current_);}
}
void DenoiseControl::Retry(Direction d) noexcept {
  if(!ValidDirection(d))return;
  std::lock_guard lock(writer_);auto& c=current_[static_cast<size_t>(d)];++c.retry;++c.revision;
  published_.Publish(current_);
}
bool DenoiseControl::Read(Direction d,ControlSnapshot& out) const noexcept {
  if(!ValidDirection(d))return false;
  std::array<ControlSnapshot,2> pair;
  if(!published_.Read(pair))return false;
  out=pair[static_cast<size_t>(d)];return true;
}
bool OverloadWindow::Observe(bool eligible,bool miss) noexcept {
  // Loading, mute and bypass do not age the deadline window and cannot count
  // as artificial successes. Only frames for which wet output was actually
  // expected participate in overload and recovery decisions.
  if(!eligible)return latched_;
  misses_-=missed_[cursor_];missed_[cursor_]=miss?1:0;misses_+=missed_[cursor_];
  cursor_=(cursor_+1)%500;if(count_<500)++count_;
  if(miss){++consecutive_misses_;consecutive_hits_=0;}
  else {consecutive_misses_=0;++consecutive_hits_;}
  if(!latched_&&(consecutive_misses_>=3||(count_==500&&misses_>25)))latched_=true;
  // Twenty consecutive on-time wet frames (200 ms) prove the worker has
  // caught up. For a full rolling window, also require the miss ratio to be
  // back within the normal <=5% budget before resuming wet output.
  if(latched_&&consecutive_hits_>=20&&(count_<500||misses_<=25))latched_=false;
  return latched_;
}
}
