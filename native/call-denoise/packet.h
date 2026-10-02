/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_PACKET_H_
#define MOLLY_DENOISE_PACKET_H_
#include <array>
#include <cstddef>
#include <cstdint>
namespace molly_denoise {
constexpr size_t kMaxFrames=480,kMaxChannels=2,kMaxSamples=kMaxFrames*kMaxChannels;
struct PacketMeta {
  uint64_t owner=0,generation=0;
  int64_t source_start=0;
  uint64_t sequence=0;
  int64_t capture_time_ns=-1,elapsed_time_ms=-1,ntp_time_ms=-1;
  uint32_t rate=0;
  uint16_t channels=0,frames=0;
  bool Valid() const noexcept {
    return owner!=0&&generation!=0&&(rate==8000||rate==16000||rate==32000||rate==48000)
        &&channels>=1&&channels<=kMaxChannels&&frames==rate/100;
  }
  size_t samples() const noexcept {return Valid()?static_cast<size_t>(frames)*channels:0;}
  bool SameInterval(const PacketMeta& other) const noexcept {
    return owner==other.owner&&generation==other.generation&&source_start==other.source_start
        &&rate==other.rate&&channels==other.channels&&frames==other.frames;
  }
};
struct OriginalPacket {PacketMeta meta;std::array<int16_t,kMaxSamples> pcm{};};
struct FilteredPacket {PacketMeta meta;std::array<float,kMaxSamples> pcm{};};
static_assert(sizeof(PacketMeta)==64);
static_assert(sizeof(OriginalPacket)==1984);
static_assert(sizeof(FilteredPacket)==3904);
}
#endif
