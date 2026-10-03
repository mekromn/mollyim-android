/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_MOCK_PACKET_H_
#define MOLLY_MOCK_PACKET_H_
#include "audio/molly_denoise/spsc.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <span>
#include <vector>
namespace molly_mock {
enum class Tap:uint32_t {BackendInput,BeforeSent,AfterSent,PlaybackReference,ReceivedResult};
constexpr size_t kTapSamples=3840;
struct TapPacket {
  uint64_t epoch=0,segment=0,configuration=0;
  int64_t source_start=0,time_ns=-1;
  Tap tap=Tap::BeforeSent;
  uint32_t rate=0,channels=0,frames=0,flags=0;
  std::array<float,kTapSamples> pcm{};
  bool Valid()const noexcept {
    const bool backend=tap==Tap::BackendInput;
    const bool rate_ok=backend?(rate>=8000&&rate<=192000&&rate%100==0):(rate==8000||rate==16000||rate==32000||rate==48000);
    return epoch&&segment&&static_cast<uint32_t>(tap)<=4&&rate_ok&&channels>=1&&channels<=2&&frames==rate/100&&frames<=(backend?1920u:480u);
  }
  size_t Count()const noexcept{return Valid()?static_cast<size_t>(frames)*channels:0;}
};
using TapQueue=molly_denoise::Spsc<TapPacket,32>;
// A working PCM view only. Caller retains the exact imported/captured file.
// Modified only with both hardware callbacks stopped, borrowed during replay.
class Clip {
 public:
  Clip()=default;Clip(const Clip&)=delete;Clip&operator=(const Clip&)=delete;
  Clip(Clip&&other)noexcept{*this=std::move(other);}
  Clip&operator=(Clip&&other)noexcept{if(this!=&other){Clear();pcm_=std::move(other.pcm_);rate_=other.rate_;channels_=other.channels_;other.rate_=other.channels_=0;}return *this;}
  bool Assign(uint32_t rate,uint32_t channels,std::vector<int16_t> samples) {
    if((rate!=8000&&rate!=16000&&rate!=32000&&rate!=48000)||channels<1||channels>2||samples.empty()||samples.size()%channels||samples.size()>static_cast<size_t>(rate)*channels*120)return false;
    pcm_=std::move(samples);rate_=rate;channels_=channels;return true;
  }
  bool Read(int64_t start,uint32_t frames,std::span<int16_t> output)const noexcept {
    if(!rate_||!frames||frames>480||static_cast<size_t>(frames)*channels_>output.size()||start>INT64_MAX-static_cast<int64_t>(frames))return false;
    for (uint32_t f = 0; f < frames; ++f) {
      for (uint32_t c = 0; c < channels_; ++c) {
        const int64_t position = start + f;
        const size_t target = static_cast<size_t>(f) * channels_ + c;
        output[target] = position >= 0 && static_cast<uint64_t>(position) < Frames()
            ? pcm_[static_cast<size_t>(position) * channels_ + c] : 0;
      }
    }
    return true;
  }
  uint32_t Rate()const noexcept{return rate_;}uint32_t Channels()const noexcept{return channels_;}
  uint64_t Frames()const noexcept{return channels_?pcm_.size()/channels_:0;}
  void Clear(){std::fill(pcm_.begin(),pcm_.end(),0);pcm_.clear();rate_=channels_=0;}
  ~Clip(){Clear();}
 private:std::vector<int16_t>pcm_;uint32_t rate_=0,channels_=0;
};
}
#endif
