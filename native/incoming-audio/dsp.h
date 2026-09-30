/* Receive-side audio processing. SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_INCOMING_AUDIO_DSP_H_
#define MOLLY_INCOMING_AUDIO_DSP_H_
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace molly_audio {
constexpr size_t kBands = 10;
constexpr size_t kParameters = 24;
constexpr size_t kMaxChannels = 32;
constexpr float kPi = 3.14159265358979323846f;
inline float Bound(float value, float low, float high, float fallback) {
  return std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}
inline float Linear(float db) { return std::pow(10.0f, db * 0.05f); }
inline float Decibels(float value) { return 20.0f * std::log10(std::max(value, 1e-6f)); }
struct Settings {
  bool enabled = false;
  bool eq_enabled = true;
  bool compressor_enabled = true;
  bool limiter_enabled = true;
  bool gain_enabled = true;
  float gain_db = 0, threshold_db = -24, ratio = 3, attack_ms = 10;
  float release_ms = 120, knee_db = 6, makeup_db = 0;
  float ceiling_db = -1, limiter_release_ms = 80;
  std::array<float, kBands> eq_db{};
  void Sanitize() {
    gain_db=Bound(gain_db,-24,18,0); threshold_db=Bound(threshold_db,-60,0,-24);
    ratio=Bound(ratio,1,20,3); attack_ms=Bound(attack_ms,0.1f,100,10);
    release_ms=Bound(release_ms,10,1000,120); knee_db=Bound(knee_db,0,24,6);
    makeup_db=Bound(makeup_db,0,18,0); ceiling_db=Bound(ceiling_db,-24,-0.1f,-1);
    limiter_release_ms=Bound(limiter_release_ms,10,1000,80);
    for (auto& v:eq_db) v=Bound(v,-12,12,0);
  }
  std::array<float,kParameters> Pack() const {
    std::array<float,kParameters> a{};
    a[0]=enabled; a[1]=eq_enabled; a[2]=compressor_enabled; a[3]=limiter_enabled;
    a[4]=gain_db; a[5]=threshold_db; a[6]=ratio; a[7]=attack_ms;
    a[8]=release_ms; a[9]=knee_db; a[10]=makeup_db;
    a[11]=ceiling_db; a[12]=limiter_release_ms;
    std::copy(eq_db.begin(),eq_db.end(),a.begin()+13); a[23]=gain_enabled;
    return a;
  }
  static Settings Unpack(const std::array<float,kParameters>& a) {
    Settings s;
    s.enabled=a[0]>0.5f; s.eq_enabled=a[1]>0.5f;
    s.compressor_enabled=a[2]>0.5f; s.limiter_enabled=a[3]>0.5f;
    s.gain_db=a[4]; s.threshold_db=a[5]; s.ratio=a[6]; s.attack_ms=a[7];
    s.release_ms=a[8]; s.knee_db=a[9]; s.makeup_db=a[10];
    s.ceiling_db=a[11]; s.limiter_release_ms=a[12];
    std::copy(a.begin()+13,a.begin()+23,s.eq_db.begin());
    s.gain_enabled=a[23]>0.5f; s.Sanitize(); return s;
  }
};

// Writers may wait; the audio reader never waits, retries or acquires the mutex.
// Atomic cells avoid the data race of a conventional seqlock over plain floats.
class SettingsBus {
 public:
  SettingsBus() { const auto a=Settings{}.Pack(); for(size_t i=0;i<kParameters;++i) cells_[i].store(a[i]); }
  void Publish(Settings s) {
    s.Sanitize(); const auto a=s.Pack(); std::lock_guard<std::mutex> lock(writer_);
    revision_.fetch_add(1); for(size_t i=0;i<kParameters;++i) cells_[i].store(a[i]);
    revision_.fetch_add(1);
  }
  bool Read(Settings& output) const {
    const uint32_t before=revision_.load(); if(before&1) return false;
    std::array<float,kParameters> a{};
    for(size_t i=0;i<kParameters;++i) a[i]=cells_[i].load();
    if(revision_.load()!=before) return false;
    output=Settings::Unpack(a); return true;
  }
 private:
  static_assert(std::atomic<float>::is_always_lock_free, "Audio settings require lock-free float atomics");
  std::array<std::atomic<float>,kParameters> cells_{};
  std::atomic<uint32_t> revision_{0}; std::mutex writer_;
};

struct Meters { float input_peak=0, output_peak=0, compression_db=0, limiting_db=0; };
struct Coefficients { float b0=1,b1=0,b2=0,a1=0,a2=0; };
struct Filter {
  Coefficients value{}, target{};
  std::array<float,kMaxChannels> z1{},z2{};
  void Smooth(float k) {
    value.b0+=k*(target.b0-value.b0); value.b1+=k*(target.b1-value.b1);
    value.b2+=k*(target.b2-value.b2); value.a1+=k*(target.a1-value.a1);
    value.a2+=k*(target.a2-value.a2);
  }
  float Tick(float x,size_t channel) {
    const float y=value.b0*x+z1[channel];
    z1[channel]=value.b1*x-value.a1*y+z2[channel]; z2[channel]=value.b2*x-value.a2*y;
    if(std::abs(z1[channel])<1e-20f) z1[channel]=0;
    if(std::abs(z2[channel])<1e-20f) z2[channel]=0;
    return y;
  }
};

class Processor {
 public:
  void Reset(bool preserve_mix=false) { filters_={}; current_gain_=1; compression_=0; limiter_gain_=1; if(!preserve_mix)wet_=0; rate_=0; channels_=0; meters_={}; }
  void PrepareSilence(bool enabled) { Reset(); wet_=enabled?1.0f:0.0f; }
  const Meters& meters() const { return meters_; }
  bool bypassed() const { return wet_ == 0; }
  bool fully_wet() const { return wet_ == 1; }
  // Normalized, interleaved float32. Does not allocate or take locks.
  void Process(float* pcm,size_t frames,size_t channels,int rate,Settings settings) {
    if(!pcm || !frames || !channels || channels>kMaxChannels || rate<8000 || rate>192000) return;
    settings.Sanitize(); meters_={};
    if(rate_!=rate || channels_!=channels) {
      filters_={}; compression_=0; limiter_gain_=1; rate_=rate; channels_=channels;
      // Do not reintroduce an un-limited dry fade on a route/channel change.
    }
    if(!settings.enabled && wet_==0) {
      for(size_t i=0;i<frames*channels;++i) meters_.input_peak=std::max(meters_.input_peak,std::abs(pcm[i]));
      meters_.output_peak=meters_.input_peak; filters_={}; compression_=0; limiter_gain_=1; current_gain_=1;
      return;
    }
    ConfigureEq(settings,rate);
    const float smooth=1-std::exp(-1.0f/(0.010f*rate));
    const float attack=std::exp(-1.0f/(0.001f*settings.attack_ms*rate));
    const float release=std::exp(-1.0f/(0.001f*settings.release_ms*rate));
    const float limit_release=std::exp(-1.0f/(0.001f*settings.limiter_release_ms*rate));
    const float target_gain=Linear((settings.gain_enabled?settings.gain_db:0)+(settings.compressor_enabled?settings.makeup_db:0));
    const float ceiling=Linear(settings.ceiling_db), fade=1.0f/(0.010f*rate);
    std::array<float,kMaxChannels> x{},dry{};
    for(size_t frame=0;frame<frames;++frame) {
      float peak=0;
      for(auto& f:filters_)f.Smooth(smooth);
      for(size_t c=0;c<channels;++c) {
        float sample=pcm[frame*channels+c]; if(!std::isfinite(sample))sample=0;
        dry[c]=sample; meters_.input_peak=std::max(meters_.input_peak,std::abs(sample));
        for(auto& f:filters_)sample=f.Tick(sample,c);
        x[c]=sample; peak=std::max(peak,std::abs(sample));
      }
      const float over=Decibels(peak)-settings.threshold_db;
      float reduction=0;
      if(settings.compressor_enabled) {
        const float slope=1-1/settings.ratio;
        if(settings.knee_db<=0)reduction=slope*std::max(over,0.0f);
        else if(over>=settings.knee_db/2)reduction=slope*over;
        else if(over>-settings.knee_db/2) {
          const float knee=over+settings.knee_db/2;
          reduction=slope*knee*knee/(2*settings.knee_db);
        }
      }
      const float coefficient=reduction>compression_?attack:release;
      compression_=coefficient*compression_+(1-coefficient)*reduction;
      current_gain_+=smooth*(target_gain-current_gain_);
      const float gain=Linear(-compression_)*current_gain_;
      wet_=settings.enabled?std::min(1.0f,wet_+fade):std::max(0.0f,wet_-fade);
      float mixed_peak=0;
      for(size_t c=0;c<channels;++c) {
        const float processed=x[c]*gain;
        x[c]=wet_==0?dry[c]:(wet_==1?processed:dry[c]+wet_*(processed-dry[c]));
        mixed_peak=std::max(mixed_peak,std::abs(x[c]));
      }
      // Limit the actual crossfaded signal, including the first enable sample.
      float required=1;
      if(settings.limiter_enabled && (settings.enabled || wet_>0) && mixed_peak>ceiling)required=ceiling/mixed_peak;
      limiter_gain_=required<limiter_gain_?required:limit_release*limiter_gain_+(1-limit_release)*required;
      const float effective_limit=settings.enabled?limiter_gain_:1+wet_*(limiter_gain_-1);
      for(size_t c=0;c<channels;++c) {
        float output=wet_==0?dry[c]:x[c]*effective_limit;
        if(settings.enabled && settings.limiter_enabled)output=std::clamp(output,-ceiling,ceiling);
        pcm[frame*channels+c]=std::isfinite(output)?output:0;
        meters_.output_peak=std::max(meters_.output_peak,std::abs(pcm[frame*channels+c]));
      }
      meters_.compression_db=std::max(meters_.compression_db,compression_);
      meters_.limiting_db=std::max(meters_.limiting_db,-Decibels(limiter_gain_));
    }
  }
 private:
  void ConfigureEq(const Settings& s,int rate) {
    constexpr std::array<float,kBands> frequencies{31.25f,62.5f,125,250,500,1000,2000,4000,8000,16000};
    for(size_t i=0;i<kBands;++i) {
      const float gain=s.eq_enabled?s.eq_db[i]:0;
      if(std::abs(gain)<1e-6f || frequencies[i]>=rate*0.45f) {filters_[i].target={};continue;}
      const float a=std::pow(10.0f,gain/40),w=2*kPi*frequencies[i]/rate;
      const float alpha=std::sin(w)/(2*1.41421356f),cosine=std::cos(w),a0=1+alpha/a;
      filters_[i].target={(1+alpha*a)/a0,-2*cosine/a0,(1-alpha*a)/a0,-2*cosine/a0,(1-alpha/a)/a0};
    }
  }
  std::array<Filter,kBands> filters_{};
  float current_gain_=1,compression_=0,limiter_gain_=1,wet_=0;
  int rate_=0; size_t channels_=0; Meters meters_{};
};
}  // namespace molly_audio
#endif
