/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_TIMELINE_H_
#define MOLLY_DENOISE_TIMELINE_H_
#include "packet.h"
#include "molly_deepfilter.h"
#include <memory>
#include <optional>
#include <span>
namespace webrtc {class PushSincResampler;}
namespace molly_denoise {
struct DelayPlan {
  uint32_t rate=0,channels=0,frames=0,total_samples=0,inner_samples=0,model_samples_48k=0;
  double converter_samples=0,padding_samples=0;
  static std::optional<DelayPlan> For(uint32_t rate,uint32_t channels,const DfMeta& meta) noexcept;
  bool Valid() const noexcept;
  std::optional<PacketMeta> Represented(const PacketMeta& input,uint32_t delay_samples) const noexcept;
};
// Owned exclusively by the audio callback; never shared directly with inference.
class DryHistory {
 public:
  bool Store(const OriginalPacket& packet) noexcept;
  bool Find(const PacketMeta& interval,OriginalPacket& packet) const noexcept;
  void Clear() noexcept {slots_.fill(OriginalPacket{});}
  ~DryHistory(){Clear();}
 private:std::array<OriginalPacket,16> slots_{};
};
class HopProcessor {
 public:
  virtual ~HopProcessor()=default;
  virtual bool Process(std::span<const float> input,std::span<float> output,float& snr) noexcept=0;
};
// Fixed padding for the measured integer-phase WebRTC 10 ms converter path.
// No interpolation, audio-callback allocation, or variable queue-based delay.
class AlignmentPad {
 public:
  explicit AlignmentPad(uint32_t samples):delay_(samples){}
  float Process(float input) noexcept;
  void Clear() noexcept {history_.fill(0);cursor_=0;}
  ~AlignmentPad(){Clear();}
 private:
  std::array<float,960> history_{};
  uint32_t cursor_=0,delay_=0;
};
// Worker-owned streaming converters. Engines are borrowed and outlive this object.
// Recreate after a generation, format, model or non-contiguous input change.
class RateAdapter {
 public:
  RateAdapter(const DelayPlan& plan,std::array<HopProcessor*,2> engines);
  ~RateAdapter();
  bool Process(const OriginalPacket& input,FilteredPacket& output) noexcept;
  int resampler_count() const noexcept{return plan_.rate==48000?0:static_cast<int>(plan_.channels*2);}
  float last_snr() const noexcept{return snr_;}
 private:
  DelayPlan plan_;
  std::array<HopProcessor*,2> engines_;
  std::array<std::unique_ptr<webrtc::PushSincResampler>,2> input_resamplers_,output_resamplers_;
  std::array<AlignmentPad,2> padding_;
  std::array<float,480> native_input_{},native_output_{},model_input_{},model_output_{};
  std::optional<PacketMeta> previous_;
  float snr_=0;
  bool valid_=false;
};
}
#endif
