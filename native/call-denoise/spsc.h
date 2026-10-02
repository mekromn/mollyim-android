/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_SPSC_H_
#define MOLLY_DENOISE_SPSC_H_
#include <array>
#include <cstddef>
#include <atomic>
#include <cstdint>
#include <type_traits>
namespace molly_denoise {
// Exactly one producer and consumer; neither may reset the other's indices.
// Epoch changes invalidate packets by metadata, not by resetting cursors.
// N is a power of two so unsigned cursor wrap keeps slot mapping continuous.
template<class T,size_t N> class Spsc {
  static_assert(N>0&&(N&(N-1))==0);
  static_assert(std::is_trivially_copyable_v<T>);
  static_assert(std::atomic<uint64_t>::is_always_lock_free);
 public:
  explicit Spsc(uint64_t initial=0) noexcept:write_(initial),read_(initial){}
  bool Push(const T& item) noexcept {
    const auto w=write_.load(std::memory_order_relaxed);
    if(w-read_.load(std::memory_order_acquire)>=N)return false;
    slots_[w&(N-1)]=item;write_.store(w+1,std::memory_order_release);return true;
  }
  bool Pop(T& item) noexcept {
    const auto r=read_.load(std::memory_order_relaxed);
    if(r==write_.load(std::memory_order_acquire))return false;
    item=slots_[r&(N-1)];slots_[r&(N-1)]=T{};
    read_.store(r+1,std::memory_order_release);return true;
  }
  // Only after producer and consumer are stopped; clears retained sample bytes.
  ~Spsc(){slots_.fill(T{});}
 private:
  std::array<T,N> slots_{};
  alignas(64) std::atomic<uint64_t> write_;
  alignas(64) std::atomic<uint64_t> read_;
};
}
#endif
