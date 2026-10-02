/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_GATE_H_
#define MOLLY_DENOISE_GATE_H_
#include "control.h"
#include "packet.h"
#include <shared_mutex>
#include <optional>
namespace molly_denoise {
enum class GateReason:uint32_t {Mute=0,RemoteMute=1,End=2,Permission=3,Route=4,Format=5,CaptureStop=6};
struct GateSnapshot {
  uint64_t owner=0,sent_generation=0,received_generation=0;
  bool active=false,microphone=false,permission=true;
  bool SendAllowed()const noexcept{return active&&microphone&&permission;}
};
// Serializes ONLY final downstream hand-off against an authoritative state
// change. Audio uses try_lock_shared and never waits. Control may wait for an
// already accepted hand-off, not for inference, loading, or worker teardown.
// A close linearizes after that hand-off completes; once it returns, no old
// lease can deliver. Callers must not call control methods while holding Lease.
class CallGate {
 public:
  class Lease {
   public:
    Lease()=default;
    explicit operator bool()const noexcept{return lock_.owns_lock();}
    Lease(Lease&&)=default;
    Lease& operator=(Lease&&)=default;
   private:
    friend class CallGate;
    explicit Lease(std::shared_mutex& mutex):lock_(mutex,std::try_to_lock){}
    std::shared_lock<std::shared_mutex> lock_;
  };
  using Invalidator=void(*)(void*,bool) noexcept;
  explicit CallGate(void* target=nullptr,Invalidator callback=nullptr):target_(target),invalidator_(callback){}
  bool BeginSession(uint64_t owner) noexcept;
  bool EndSession(uint64_t owner) noexcept;
  bool SetGate(uint64_t owner,bool allowed,GateReason reason) noexcept;
  bool Invalidate(uint64_t owner,GateReason reason) noexcept;
  GateSnapshot Read()const noexcept;
  Lease Acquire(uint64_t owner,uint64_t generation,Direction direction) noexcept;
  bool Closing()const noexcept{return closing_.load();}
 private:
  bool Change(uint64_t owner,bool allowed,GateReason reason,bool begin,bool invalidate) noexcept;
  std::mutex writer_;
  std::shared_mutex handoff_;
  std::atomic<bool> closing_{false};
  AtomicSnapshot<GateSnapshot> snapshot_;
  GateSnapshot current_;
  void* target_;
  Invalidator invalidator_;
};
// Separate gate and stream generations are intentional: format changes occur
// on capture callbacks and must never wait for a control-plane mutex. All four
// fields travel with AudioFrame through APM/async processing/CopyFrom.
struct CaptureStamp {
  uint64_t owner=0,gate_generation=0,stream_generation=0;
  int64_t source_start=0;
  bool Valid()const noexcept{return owner&&gate_generation&&stream_generation&&source_start>=0;}
};
class CaptureClock {
 public:
  CaptureStamp Stamp(const GateSnapshot&,uint32_t rate,uint16_t channels,uint16_t frames) noexcept;
  bool IsCurrent(const CaptureStamp& s)const noexcept{return s.Valid()&&s.stream_generation==current_.load();}
 private:
  uint64_t counter_=0;
  PacketMeta previous_;
  std::atomic<uint64_t> current_{0};
};
}
#endif
