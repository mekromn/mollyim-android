/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_INCOMING_EFFECT_SCOPE_H_
#define MOLLY_INCOMING_EFFECT_SCOPE_H_
#include "dsp.h"
namespace molly_audio {
// Owns configuration and numeric meters only. Streaming DSP remains per transport.
struct EffectScope {
  SettingsBus bus;
  std::atomic<uint32_t> reset_epoch{0};
  std::atomic<uint32_t> frame_counter{0};
  std::array<std::atomic<float>,6> meter_values{};
};
inline EffectScope& ProductionEffectScope() {
  static auto* const scope=new EffectScope();
  return *scope;
}
}
#endif
