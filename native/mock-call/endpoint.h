/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_MOCK_ENDPOINT_H_
#define MOLLY_MOCK_ENDPOINT_H_
#include "registry.h"
#include "audio/molly_denoise/observer.h"
#include "api/audio/audio_device.h"
#include "api/audio/audio_mixer.h"
#include "api/task_queue/task_queue_base.h"
#include "call/audio_sender.h"
#include <optional>
namespace webrtc {class AudioTransportImpl;}
namespace molly_mock {
class LabEndpoint final:public webrtc::AudioMixer::Source,public webrtc::AudioSender,public molly_denoise::PathObserver {
 public:
  LabEndpoint(std::shared_ptr<LabSession>,webrtc::AudioTransportImpl&,webrtc::AudioMixer&,webrtc::AudioDeviceModule*,webrtc::TaskQueueBase*);
  ~LabEndpoint()override;
  LabEndpoint(const LabEndpoint&)=delete;
  bool Attached()const noexcept{return attached_;}
  // Factory worker only; actual ADM starts/stops remain on their original queue.
  bool Execute(int opcode,int64_t a,int64_t b,int64_t c,int64_t d);
  void Stop();
  void ApplyEffective();
  AudioFrameInfo GetAudioFrameWithInfo(int,webrtc::AudioFrame*)override;
  int Ssrc()const override{return 1;}
  int PreferredSampleRate()const override;
  void SendAudioData(std::unique_ptr<webrtc::AudioFrame>)override;
  void Before(bool,const molly_denoise::OriginalPacket&)noexcept override;
  void After(bool,const molly_denoise::ProcessResult&,std::span<const float>)noexcept override;
  uint64_t CaptureInput(std::span<const int16_t>,uint32_t,uint32_t,int64_t)noexcept;
  class SendLease {
   public:
    SendLease()=default;explicit SendLease(std::atomic_flag& flag):flag_(&flag){}
    SendLease(SendLease&& s)noexcept:flag_(s.flag_){s.flag_=nullptr;}
    ~SendLease(){if(flag_)flag_->clear();}
    explicit operator bool()const noexcept{return flag_!=nullptr;}
   private:std::atomic_flag*flag_=nullptr;
  };
  SendLease BeginSent(const webrtc::AudioFrame&)noexcept;
  void AfterMix(webrtc::AudioFrame&)noexcept;
  bool AcceptsCapture()const noexcept;
 private:
  bool Start(int mode,bool live,int monitor,uint32_t taps);
  void FillFrame(const Clip&,int64_t,uint32_t,webrtc::AudioFrame&);
  void Publish(Tap,const molly_denoise::PacketMeta&,std::span<const float>,uint64_t,uint32_t)noexcept;
  int64_t ClipPosition(bool,const molly_denoise::PacketMeta&)const noexcept;
  bool InSelection(bool,int64_t)const noexcept;
  bool ReadyForPreroll()const noexcept;
  std::shared_ptr<LabSession> session_;
  webrtc::AudioTransportImpl&transport_;
  webrtc::AudioMixer&mixer_;
  webrtc::AudioDeviceModule*adm_;
  bool attached_=false,source_added_=false,live_=false;
  int mode_=0;
  std::atomic<int>monitor_{0};
  uint64_t segment_=1,render_epoch_=0,send_epoch_=0;
  std::atomic_flag sent_busy_=ATOMIC_FLAG_INIT;
  std::atomic<bool>original_{false};
  int64_t start_ms_=0,end_ms_=120000,preroll_tick_=0,render_tick_=0,raw_position_=0;
  bool started_preroll_=false;
  std::array<int64_t,2>last_input_{-480,-480},origins_{0,0};
  std::array<bool,2>complete_{false,false};
  std::array<molly_denoise::DryHistory,2>dry_;
  molly_denoise::Spsc<TapPacket,16>sent_monitor_;
  TapPacket send_work_,render_work_,raw_work_,received_original_;
  webrtc::AudioFrame replay_frame_;
  std::array<int16_t,960>clip_scratch_{};
};
}
#endif
