/* Receive mixer adapter. SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_INCOMING_AUDIO_ADAPTER_H_
#define MOLLY_INCOMING_AUDIO_ADAPTER_H_
#include "audio/molly_incoming/dsp.h"
#include "api/audio/audio_frame.h"
#include <chrono>

namespace molly_audio {
// One configuration per app process; filter/envelope state remains per transport.
inline SettingsBus& GetSettingsBus() {
  // Process-lifetime control data only; no audio is retained here. Constructed
  // by ReceiveProcessor before callbacks start, never in Process(). Avoid an
  // exit-time mutex destructor racing native library teardown.
  static auto* const bus = new SettingsBus();
  return *bus;
}
inline std::atomic<uint32_t> reset_epoch{0};
inline std::atomic<uint32_t> frame_counter{0};
inline std::array<std::atomic<float>,6> meter_values{};
class ReceiveProcessor {
 public:
  void Process(webrtc::AudioFrame* frame
#ifdef MOLLY_CALL_DENOISE
      , molly_denoise::CallTransport* denoiser = nullptr
#endif
  ) {
    const auto now=std::chrono::steady_clock::now();
    if(last_frame_!=std::chrono::steady_clock::time_point{} && now-last_frame_>std::chrono::milliseconds(250))processor_.Reset(true);
    last_frame_=now;
    const auto epoch=reset_epoch.load(std::memory_order_relaxed);
    if(epoch_!=epoch) {processor_.Reset();epoch_=epoch;}
    settings_bus_.Read(settings_);  // Keep last coherent settings if writer is busy.
    const size_t count=frame->samples_per_channel()*frame->num_channels();
    if(count>scratch_.size() || frame->num_channels()>kMaxChannels || !count) return;
    const auto input=frame->data_view();
    if(input.size()!=count) return;
    Meters meters;
    bool denoised=false;
#ifdef MOLLY_CALL_DENOISE
    std::optional<molly_denoise::CallTransport::Lease> receive_lease;
    if(denoiser) {
      auto packet=molly_denoise::ReadFrame(*frame);
      receive_lease.emplace(denoiser->PrepareReceived(packet));
      if(!*receive_lease) {
        frame->Mute();processor_.Reset(true);
        meter_values[0].store(-120.f);meter_values[1].store(-120.f);
        meter_values[2].store(0.f);meter_values[3].store(0.f);
        return;
      }
      if(packet.meta.owner!=denoise_owner_||packet.meta.generation!=denoise_generation_) {
        processor_.Reset(true);denoise_owner_=packet.meta.owner;denoise_generation_=packet.meta.generation;
      }
      const auto result=denoiser->ProcessReceived(packet,std::span(scratch_).first(count));
      denoised=result.kind!=molly_denoise::OutputKind::Direct;
      if(denoised)molly_denoise::ApplyFrameMetadata(*frame,result.source);
    }
#endif
    if(frame->muted()&&!denoised) {
      processor_.PrepareSilence(settings_.enabled);
    } else if(!denoised && !settings_.enabled && processor_.bypassed()) {
      // Preserve the original int16 buffer exactly, including its mute state.
      for(size_t i=0;i<count;++i)meters.input_peak=std::max(meters.input_peak,std::abs(input[i]/32768.0f));
      meters.output_peak=meters.input_peak;
    } else {
      if(!denoised)for(size_t i=0;i<count;++i)scratch_[i]=input[i]/32768.0f;
      processor_.Process(std::span(scratch_).first(count),frame->samples_per_channel(),frame->num_channels(),frame->sample_rate_hz(),settings_);
      auto pcm=frame->mutable_data(frame->samples_per_channel(),frame->num_channels());
      const bool limiting=settings_.enabled && settings_.limiter_enabled;
      const int peak_bound=static_cast<int>(std::floor(Linear(settings_.ceiling_db)*32768));
      for(size_t i=0;i<count;++i) {
        int value=static_cast<int>(std::lrint(std::clamp(scratch_[i],-1.0f,32767.0f/32768)*32768));
        if(limiting)value=std::clamp(value,-peak_bound,peak_bound);
        pcm[i]=static_cast<int16_t>(value);
      }
      meters=processor_.meters();
    }
    meter_values[0].store(Decibels(meters.input_peak),std::memory_order_relaxed);
    meter_values[1].store(Decibels(meters.output_peak),std::memory_order_relaxed);
    meter_values[2].store(meters.compression_db,std::memory_order_relaxed);
    meter_values[3].store(meters.limiting_db,std::memory_order_relaxed);
    meter_values[4].store(frame->sample_rate_hz(),std::memory_order_relaxed);
    meter_values[5].store(frame->num_channels(),std::memory_order_relaxed);
    frame_counter.fetch_add(1,std::memory_order_relaxed);
  }
 private:
  SettingsBus& settings_bus_=GetSettingsBus();
  std::chrono::steady_clock::time_point last_frame_{};
  Processor processor_;
  Settings settings_;
  uint32_t epoch_=0;
#ifdef MOLLY_CALL_DENOISE
  uint64_t denoise_owner_=0,denoise_generation_=0;
#endif
  std::array<float,webrtc::AudioFrame::kMaxDataSizeSamples> scratch_{};
};
}
#endif
