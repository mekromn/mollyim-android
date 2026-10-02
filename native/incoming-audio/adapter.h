/* Receive mixer adapter. SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_INCOMING_AUDIO_ADAPTER_H_
#define MOLLY_INCOMING_AUDIO_ADAPTER_H_
#include "audio/molly_incoming/dsp.h"
#include "audio/molly_incoming/effect_scope.h"
#include "api/audio/audio_frame.h"
#include <chrono>

namespace molly_audio {
// Production ABI still uses this bus; lab instances use their own scope.
inline SettingsBus& GetSettingsBus() {return ProductionEffectScope().bus;}
class ReceiveProcessor {
 public:
  explicit ReceiveProcessor(EffectScope& scope=ProductionEffectScope()):scope_(scope),settings_bus_(scope.bus){}
  void Process(webrtc::AudioFrame* frame
#ifdef MOLLY_CALL_DENOISE
      , molly_denoise::CallTransport* denoiser = nullptr, molly_denoise::PathObserver* observer = nullptr
#endif
  ) {
    const auto now=std::chrono::steady_clock::now();
    if(last_frame_!=std::chrono::steady_clock::time_point{} && now-last_frame_>std::chrono::milliseconds(250))processor_.Reset(true);
    last_frame_=now;
    const auto epoch=scope_.reset_epoch.load(std::memory_order_relaxed);
    if(epoch_!=epoch) {processor_.Reset();epoch_=epoch;}
    settings_bus_.Read(settings_);  // Keep last coherent settings if writer is busy.
    const size_t count=frame->samples_per_channel()*frame->num_channels();
    if(count>scratch_.size() || frame->num_channels()>kMaxChannels || !count) return;
    const auto input=frame->data_view();
    if(input.size()!=count) return;
    Meters meters;
    bool denoised=false;
#ifdef MOLLY_CALL_DENOISE
    bool original_pcm_preserved=false;
#endif
#ifdef MOLLY_CALL_DENOISE
    std::optional<molly_denoise::CallTransport::Lease> receive_lease;
    molly_denoise::ProcessResult observed_result;
    if(denoiser) {
      auto packet=molly_denoise::ReadFrame(*frame);
      receive_lease.emplace(denoiser->PrepareReceived(packet));
      if(!*receive_lease) {
        frame->Mute();processor_.Reset(true);
        scope_.meter_values[0].store(-120.f);scope_.meter_values[1].store(-120.f);
        scope_.meter_values[2].store(0.f);scope_.meter_values[3].store(0.f);
        return;
      }
      if(packet.meta.owner!=denoise_owner_||packet.meta.generation!=denoise_generation_) {
        processor_.Reset(true);denoise_owner_=packet.meta.owner;denoise_generation_=packet.meta.generation;
      }
      if(observer)observer->Before(true,packet);
      const auto result=denoiser->ProcessReceived(packet,std::span(scratch_).first(count));
      observed_result=result;
      denoised=result.kind!=molly_denoise::OutputKind::Direct;
      if(denoised)molly_denoise::ApplyFrameMetadata(*frame,result.source);
    }
#endif
    if(frame->muted()&&!denoised) {
      processor_.PrepareSilence(settings_.enabled);
    } else if(!denoised && !settings_.enabled && processor_.bypassed()) {
#ifdef MOLLY_CALL_DENOISE
      original_pcm_preserved=true;
#endif
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
#ifdef MOLLY_CALL_DENOISE
    if(observer&&denoiser) {
      if(original_pcm_preserved) {
        for(size_t i=0;i<count;++i)scratch_[i]=input[i]/32768.f;
      } else if(frame->muted()&&!denoised) {std::fill_n(scratch_.begin(),count,0.f);}
      observer->After(true,observed_result,std::span(scratch_).first(count));
    }
#endif
    scope_.meter_values[0].store(Decibels(meters.input_peak),std::memory_order_relaxed);
    scope_.meter_values[1].store(Decibels(meters.output_peak),std::memory_order_relaxed);
    scope_.meter_values[2].store(meters.compression_db,std::memory_order_relaxed);
    scope_.meter_values[3].store(meters.limiting_db,std::memory_order_relaxed);
    scope_.meter_values[4].store(frame->sample_rate_hz(),std::memory_order_relaxed);
    scope_.meter_values[5].store(frame->num_channels(),std::memory_order_relaxed);
    scope_.frame_counter.fetch_add(1,std::memory_order_relaxed);
  }
 private:
  EffectScope& scope_;
  SettingsBus& settings_bus_;
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
