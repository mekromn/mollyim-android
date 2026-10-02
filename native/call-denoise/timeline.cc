/* SPDX-License-Identifier: AGPL-3.0-only */
#include "timeline.h"
#include "common_audio/resampler/push_sinc_resampler.h"
#include <algorithm>
#include <cmath>
#include <limits>
namespace molly_denoise {
std::optional<DelayPlan> DelayPlan::For(uint32_t rate,uint32_t channels,const DfMeta& m) noexcept {
  if((rate!=8000&&rate!=16000&&rate!=32000&&rate!=48000)||channels<1||channels>2
     ||m.abi_version!=1||m.sample_rate!=48000||m.hop!=480||m.fft!=960||m.df_order!=5
     ||(m.lookahead!=0&&m.lookahead!=2)||m.intrinsic_delay!=480+480*m.lookahead)return std::nullopt;
  const uint32_t q=rate/100;
  // PushSincResampler primes by discarding floor(ChunkSize()) output samples.
  // Its half-kernel group-delay estimate omits that integer priming step.
  // For the supported Q<->480 blocks the native round-trip delays are
  // 19, 22, 27 samples (8/16/32 kHz), not 18.667, 21.333, 26.667.
  const double converter=rate==48000?0:16.+std::ceil(16.*rate/48000.);
  const uint32_t model_native=(1+m.lookahead)*q;
  const uint32_t inner=model_native+(rate==48000?0:q);
  return DelayPlan{rate,channels,q,inner+2*q,inner,m.intrinsic_delay,converter,inner-model_native-converter};
}
bool DelayPlan::Valid() const noexcept {
  if(model_samples_48k!=480&&model_samples_48k!=1440)return false;
  const DfMeta m{1,48000,480,960,model_samples_48k/480-1,5,model_samples_48k};
  const auto expected=For(rate,channels,m);
  return expected&&frames==expected->frames&&total_samples==expected->total_samples
      &&inner_samples==expected->inner_samples&&converter_samples==expected->converter_samples
      &&padding_samples==expected->padding_samples;
}
std::optional<PacketMeta> DelayPlan::Represented(const PacketMeta& in,uint32_t delay) const noexcept {
  if(!Valid()||!in.Valid()||in.rate!=rate||in.channels!=channels||delay%frames!=0
      ||in.source_start<std::numeric_limits<int64_t>::min()+delay)return std::nullopt;
  PacketMeta out=in;out.source_start-=delay;out.sequence-=delay/frames;
  const int64_t ns=static_cast<int64_t>(delay)*1000000000/rate,ms=ns/1000000;
  const auto shift=[](int64_t time,int64_t amount){return time<0||time<amount?-1:time-amount;};
  out.capture_time_ns=shift(in.capture_time_ns,ns);out.elapsed_time_ms=shift(in.elapsed_time_ms,ms);out.ntp_time_ms=shift(in.ntp_time_ms,ms);
  return out;
}
bool DryHistory::Store(const OriginalPacket& p) noexcept {
  if(!p.meta.Valid())return false;
  slots_[p.meta.sequence&15]=p;return true;
}
bool DryHistory::Find(const PacketMeta& interval,OriginalPacket& p) const noexcept {
  if(!interval.Valid())return false;
  for(const auto& slot:slots_)if(slot.meta.Valid()&&slot.meta.SameInterval(interval)){p=slot;return true;}
  return false;
}
float AlignmentPad::Process(float in) noexcept {
  if(delay_==0)return in;
  if(delay_>=history_.size())return 0;
  history_[cursor_]=in;
  const auto out=history_[(cursor_+history_.size()-delay_)%history_.size()];
  if(++cursor_==history_.size())cursor_=0;
  return out;
}
RateAdapter::RateAdapter(const DelayPlan& plan,std::array<HopProcessor*,2> engines)
    :plan_(plan),engines_(engines),padding_{AlignmentPad(0),AlignmentPad(0)} {
  valid_=plan.Valid();
  if(!valid_)return;
  padding_={AlignmentPad(static_cast<uint32_t>(plan.padding_samples)),AlignmentPad(static_cast<uint32_t>(plan.padding_samples))};
  if(plan.rate!=48000)for(size_t c=0;c<plan.channels;++c){
    input_resamplers_[c]=std::make_unique<webrtc::PushSincResampler>(plan.frames,480);
    output_resamplers_[c]=std::make_unique<webrtc::PushSincResampler>(480,plan.frames);
  }
}
RateAdapter::~RateAdapter(){native_input_.fill(0);native_output_.fill(0);model_input_.fill(0);model_output_.fill(0);}
bool RateAdapter::Process(const OriginalPacket& in,FilteredPacket& out) noexcept {
  out={};snr_=0;
  const auto failed=[&]() noexcept {valid_=false;snr_=0;out={};return false;};
  if(!valid_)return failed();
  const auto represented=plan_.Represented(in.meta,plan_.inner_samples);
  if(!represented)return failed();
  if(previous_&&(in.meta.owner!=previous_->owner||in.meta.generation!=previous_->generation
      ||previous_->source_start>std::numeric_limits<int64_t>::max()-previous_->frames
      ||in.meta.source_start!=previous_->source_start+previous_->frames))return failed();
  for(size_t c=0;c<plan_.channels;++c){
    if(!engines_[c])return failed();
    for(size_t i=0;i<plan_.frames;++i)native_input_[i]=in.pcm[i*plan_.channels+c]/32768.f;
    if(input_resamplers_[c])input_resamplers_[c]->Resample(std::span<const float>(native_input_).first(plan_.frames),std::span<float>(model_input_));
    else model_input_=native_input_;
    float channel_snr=0;
    if(!engines_[c]->Process(model_input_,model_output_,channel_snr)||!std::isfinite(channel_snr))return failed();
    for(float sample:model_output_)if(!std::isfinite(sample))return failed();
    if(output_resamplers_[c])output_resamplers_[c]->Resample(std::span<const float>(model_output_),std::span<float>(native_output_).first(plan_.frames));
    else native_output_=model_output_;
    for(size_t i=0;i<plan_.frames;++i){float v=padding_[c].Process(native_output_[i]);if(!std::isfinite(v))return failed();out.pcm[i*plan_.channels+c]=v;}
    snr_+=channel_snr/static_cast<float>(plan_.channels);
  }
  previous_=in.meta;out.meta=*represented;return true;
}
}
