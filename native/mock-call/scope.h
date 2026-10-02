/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_MOCK_SCOPE_H_
#define MOLLY_MOCK_SCOPE_H_
#include "audio/molly_denoise/control.h"
#include "audio/molly_incoming/effect_scope.h"
namespace molly_mock {
struct ScopeConfiguration {
  molly_denoise::DenoiseConfig received, sent;
  molly_audio::Settings effects;
  uint64_t revision=0;
};
// Owned by one local AudioState. Construction does not allocate a model or ADM.
class AudioScope {
 public:
  bool Configure(const molly_denoise::DenoiseConfig&,const molly_denoise::DenoiseConfig&,const molly_audio::Settings&) noexcept;
  uint64_t Revision()const noexcept {ScopeConfiguration c;configuration_.Read(c);return c.revision;}
  bool Read(ScopeConfiguration& out)const noexcept{return configuration_.Read(out);}
  molly_denoise::DenoiseControl denoise;
  molly_audio::EffectScope effects;
 private:
  std::mutex writer_;
  ScopeConfiguration current_;
  molly_denoise::AtomicSnapshot<ScopeConfiguration> configuration_;
};
}
#endif
