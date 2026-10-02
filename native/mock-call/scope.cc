/* SPDX-License-Identifier: AGPL-3.0-only */
#include "scope.h"
#include <limits>
namespace molly_mock {
bool AudioScope::Configure(const molly_denoise::DenoiseConfig& received,const molly_denoise::DenoiseConfig& sent,const molly_audio::Settings& effect)noexcept {
  if(!received.Valid()||!sent.Valid())return false;
  auto safe=effect;safe.Sanitize();if(safe.Pack()!=effect.Pack())return false;
  std::lock_guard lock(writer_);
  if(current_.revision==std::numeric_limits<uint64_t>::max())return false;
  if(!denoise.UpdateBoth(received,sent))return false;
  effects.bus.Publish(safe);
  current_={received,sent,safe,current_.revision+1};configuration_.Publish(current_);return true;
}
}
