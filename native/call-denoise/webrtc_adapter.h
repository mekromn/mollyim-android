/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_WEBRTC_ADAPTER_H_
#define MOLLY_DENOISE_WEBRTC_ADAPTER_H_
#define MOLLY_CALL_DENOISE 1
#include "service.h"
#include "api/audio/audio_frame.h"
#include <algorithm>
#include <cmath>
#include <optional>
namespace molly_denoise {
inline OriginalPacket ReadFrame(const webrtc::AudioFrame& frame)noexcept {
  OriginalPacket p;
  p.meta.rate=static_cast<uint32_t>(frame.sample_rate_hz());
  p.meta.frames=static_cast<uint16_t>(frame.samples_per_channel());
  p.meta.channels=static_cast<uint16_t>(frame.num_channels());
  p.meta.elapsed_time_ms=frame.elapsed_time_ms_;p.meta.ntp_time_ms=frame.ntp_time_ms_;
  const auto time=frame.absolute_capture_timestamp_ms();
  if(time&&*time>=0&&*time<=INT64_MAX/1000000)p.meta.capture_time_ns=*time*1000000;
  // Temporary tags permit geometry validation before the actual capture/render
  // owner is applied. Unsupported shapes stay in the ordinary WebRTC path.
  p.meta.owner=1;p.meta.generation=1;
  const auto count=p.meta.samples();const auto data=frame.data_view();
  if(count&&count==data.size())for(size_t i=0;i<count;++i)p.pcm[i]=data[i];
  return p;
}
inline void ApplyFrameMetadata(webrtc::AudioFrame& frame,const PacketMeta& meta)noexcept {
  frame.elapsed_time_ms_=meta.elapsed_time_ms;frame.ntp_time_ms_=meta.ntp_time_ms;
  if(meta.capture_time_ns>=0)frame.set_absolute_capture_timestamp_ms(meta.capture_time_ns/1000000);
  else frame.clear_absolute_capture_timestamp();
}
inline void WriteFrame(webrtc::AudioFrame& frame,std::span<const float> output)noexcept {
  const auto count=frame.samples_per_channel()*frame.num_channels();
  if(count>output.size()){frame.Mute();return;}
  auto data=frame.mutable_data(frame.samples_per_channel(),frame.num_channels());
  for(size_t i=0;i<count;++i)data[i]=static_cast<int16_t>(std::lrint(std::clamp(output[i],-1.f,32767.f/32768)*32768));
}
class WebRtcDenoiser {
 public:
  WebRtcDenoiser():binding_(GlobalService()){}
  bool Bound()const noexcept{return binding_.Bound();}
  CallTransport* Receiver()noexcept{return Bound()?&binding_.Core():nullptr;}
  void StampCapture(webrtc::AudioFrame* frame)noexcept {
    if(!Bound())return;
    const auto stamp=binding_.Core().StampCapture(frame->sample_rate_hz(),static_cast<uint16_t>(frame->num_channels()),static_cast<uint16_t>(frame->samples_per_channel()));
    frame->molly_owner_=stamp.owner;frame->molly_gate_generation_=stamp.gate_generation;
    frame->molly_stream_generation_=stamp.stream_generation;frame->molly_source_start_=stamp.source_start;
  }
  CallTransport::Lease ProcessSent(webrtc::AudioFrame* frame)noexcept {
    auto packet=ReadFrame(*frame);
    const CaptureStamp stamp{frame->molly_owner_,frame->molly_gate_generation_,frame->molly_stream_generation_,frame->molly_source_start_};
    auto lease=binding_.Core().PrepareSent(stamp,packet);
    if(!lease)return {};
    const auto result=binding_.Core().ProcessSent(packet,sent_scratch_);
    if(result.kind!=OutputKind::Direct){WriteFrame(*frame,sent_scratch_);ApplyFrameMetadata(*frame,result.source);}
    return lease;
  }
  void CaptureStopped()noexcept {
    if(!Bound())return;
    auto& gate=binding_.Core().Gate();gate.Invalidate(gate.Read().owner,GateReason::CaptureStop);
  }
 private:
  TransportBinding binding_;
  std::array<float,kMaxSamples> sent_scratch_{};
};
}
#endif
