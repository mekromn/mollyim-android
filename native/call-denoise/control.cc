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
  published_[static_cast<size_t>(d)].Publish(c);return true;
}
void DenoiseControl::Bypass(Direction d,bool bypass) noexcept {
  if(!ValidDirection(d))return;
  std::lock_guard lock(writer_);auto& c=current_[static_cast<size_t>(d)];
  if(c.bypassed!=bypass){c.bypassed=bypass;++c.revision;published_[static_cast<size_t>(d)].Publish(c);}
}
void DenoiseControl::Retry(Direction d) noexcept {
  if(!ValidDirection(d))return;
  std::lock_guard lock(writer_);auto& c=current_[static_cast<size_t>(d)];++c.retry;++c.revision;
  published_[static_cast<size_t>(d)].Publish(c);
}
bool DenoiseControl::Read(Direction d,ControlSnapshot& out) const noexcept {
  return ValidDirection(d)&&published_[static_cast<size_t>(d)].Read(out);
}
bool OverloadWindow::Observe(bool eligible,bool miss) noexcept {
  miss=eligible&&miss;misses_-=missed_[cursor_];missed_[cursor_]=miss?1:0;misses_+=missed_[cursor_];
  cursor_=(cursor_+1)%500;if(count_<500)++count_;
  consecutive_=miss?consecutive_+1:0;
  latched_=latched_||consecutive_>=3||(count_==500&&misses_>25);return latched_;
}
}
