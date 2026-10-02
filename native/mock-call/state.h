/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_MOCK_STATE_H_
#define MOLLY_MOCK_STATE_H_
#include "packet.h"
#include "audio/molly_denoise/control.h"
#include <atomic>
#include <memory>
namespace molly_mock {
struct TapLevels {
 uint64_t blocks=0,samples=0,overs=0,saturation=0,epoch=0;
 float peak=0,rms=0;
 uint32_t rate=0,channels=0;
 int64_t source_start=0,time_ns=-1;
};
class LabState {
 public:
  LabState();
  uint64_t Epoch()const noexcept{return epoch_.load();}
  uint64_t Segment()const noexcept{return segment_.load();}
  bool Revoked()const noexcept{return revoked_.load();}
  bool Allowed(uint64_t epoch)const noexcept{return !revoked_.load()&&running_.load()&&epoch==epoch_.load();}
  bool Start(uint64_t segment,uint32_t recording_mask)noexcept;
  void Quiesce()noexcept {running_.store(false);mask_.store(0);}
  void Stop()noexcept {Quiesce();epoch_.fetch_add(1);drain_epoch_.store(0);}
  void Revoke()noexcept {revoked_.store(true);Stop();}
  void Mute(bool muted)noexcept {muted_.store(muted);const auto e=epoch_.fetch_add(1)+1;drain_epoch_.store(e);}
  bool Muted()const noexcept{return muted_.load();}
  bool Observe(const TapPacket&)noexcept;
  bool Drain(Tap,TapPacket&)noexcept;
  TapLevels Levels(Tap)const noexcept;
  uint64_t Dropped()const noexcept{return dropped_.load();}
  bool RecordingIncomplete()const noexcept{return incomplete_.load();}
 private:
  std::atomic<uint64_t>epoch_{1},segment_{0},drain_epoch_{0},dropped_{0};
  std::atomic<uint32_t>mask_{0};
  std::atomic<bool>running_{false},revoked_{false},muted_{false},incomplete_{false};
  std::array<std::unique_ptr<TapQueue>,5>queues_;
  std::array<molly_denoise::AtomicSnapshot<TapLevels>,5>levels_;
};
}
#endif
