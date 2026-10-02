/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_WORKER_H_
#define MOLLY_DENOISE_WORKER_H_
#include "library.h"
#include <memory>
namespace molly_denoise {
enum class EffectiveState:uint32_t {Off,Loading,Active,ManualBypass,Unavailable,Overloaded,Muted,Unsupported,ZeroSuppression};
enum class OutputKind:uint32_t {Direct,DelayedDry,Wet,Muted,Transition};
struct ProcessResult {OutputKind kind=OutputKind::Direct;EffectiveState state=EffectiveState::Off;PacketMeta source;};
struct DenoiseStatus {
  EffectiveState state=EffectiveState::Off;
  uint32_t rate=0,channels=0,delay_samples=0,misses=0;
  float input_peak=0,output_peak=0,snr=0,mean_us=0,p95_us=0;
  bool levels_valid=false,inference_valid=false;
  uint64_t processed=0,updated_us=0;
};
class DirectionProcessor {
 public:
  // Construction/destruction MUST be off real-time callbacks. Factory/control
  // outlive this object. Thread=false is deterministic test execution only.
  DirectionProcessor(DenoiseControl&,Direction,EngineFactory&,bool thread=true);
  ~DirectionProcessor();
  DirectionProcessor(const DirectionProcessor&)=delete;
  DirectionProcessor& operator=(const DirectionProcessor&)=delete;
  // One caller per direction. No allocation, waiting, file IO, or model work.
  ProcessResult Process(const OriginalPacket&,std::span<float> output) noexcept;
  DenoiseStatus Status() const noexcept;
  // Non-blocking cancellation when capture stops (no future callback is
  // required). Task 5's authoritative gate decides whether a new epoch may run.
  void Invalidate() noexcept;
  // Same worker iteration as the real thread, never call when thread=true.
  void PumpForTest();
 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
}
#endif
