/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_CONTROL_H_
#define MOLLY_DENOISE_CONTROL_H_
#include "molly_deepfilter.h"
#include <array>
#include <bit>
#include <atomic>
#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <mutex>
#include <type_traits>
namespace molly_denoise {
// All payload words are atomic: a conventional seqlock over non-atomic data
// would still be a C++ data race. Reads are bounded and leave output unchanged
// when the writer is busy. A publisher has exactly one serialized writer.
template<class T> class AtomicSnapshot {
  static_assert(std::is_trivially_copyable_v<T>);
  static_assert(std::atomic<uint64_t>::is_always_lock_free);
  static constexpr size_t kWords=(sizeof(T)+7)/8;
 public:
  AtomicSnapshot() noexcept { Publish(T{}); }
  void Publish(const T& value) noexcept {
    std::array<std::byte,kWords*sizeof(uint64_t)> padded{};
    const auto bytes=std::bit_cast<std::array<std::byte,sizeof(T)>>(value);
    std::copy(bytes.begin(),bytes.end(),padded.begin());
    const auto raw=std::bit_cast<std::array<uint64_t,kWords>>(padded);
    sequence_.fetch_add(1);
    for(size_t i=0;i<kWords;++i)words_[i].store(raw[i]);
    sequence_.fetch_add(1);
  }
  bool Read(T& value) const noexcept {
    for(int attempt=0;attempt<2;++attempt){
      const auto a=sequence_.load();if(a&1)return false;
      std::array<uint64_t,kWords> raw{};
      for(size_t i=0;i<kWords;++i)raw[i]=words_[i].load();
      if(a==sequence_.load()){std::array<std::byte,sizeof(T)> bytes{};const auto padded=std::bit_cast<std::array<std::byte,kWords*sizeof(uint64_t)>>(raw);std::copy_n(padded.begin(),sizeof(T),bytes.begin());value=std::bit_cast<T>(bytes);return true;}
    }
    return false;
  }
 private:
  std::atomic<uint64_t> sequence_{0};
  std::array<std::atomic<uint64_t>,kWords> words_{};
};
enum class Direction:uint32_t {Received=0,Sent=1};
enum class Model:uint32_t {Standard=0,LowLatency=1};
struct DenoiseConfig {
  bool enabled=false;
  Model model=Model::Standard;
  DfConfig parameters{1,30.f,0,.02f,-10.f,30.f,20.f,0};
  bool Valid() const noexcept;
};
struct ControlSnapshot {DenoiseConfig config;uint64_t revision=0,retry=0;bool bypassed=false;};
class DenoiseControl {
 public:
  bool Update(Direction,const DenoiseConfig&) noexcept;
  bool UpdateBoth(const DenoiseConfig&,const DenoiseConfig&) noexcept;
  bool ReadBoth(std::array<ControlSnapshot,2>& out)const noexcept{return published_.Read(out);}
  void Bypass(Direction,bool) noexcept;
  void Retry(Direction) noexcept;
  bool Read(Direction,ControlSnapshot&) const noexcept;
 private:
  std::mutex writer_;
  std::array<ControlSnapshot,2> current_{};
  AtomicSnapshot<std::array<ControlSnapshot,2>> published_;
};
// Exactly 500 10-ms callbacks. Loading/mute/bypass are ineligible time slots,
// not artificial successes. The ratio rule starts at a full five seconds.
class OverloadWindow {
 public:
  bool Observe(bool eligible,bool missed) noexcept;
  void Reset() noexcept {*this=OverloadWindow{};}
  bool latched() const noexcept{return latched_;}
  uint32_t misses() const noexcept{return misses_;}
 private:
  std::array<uint8_t,500> missed_{};
  uint32_t cursor_=0,count_=0,misses_=0,consecutive_=0;
  bool latched_=false;
};
}
#endif
