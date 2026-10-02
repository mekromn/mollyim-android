/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_TRANSPORT_H_
#define MOLLY_DENOISE_TRANSPORT_H_
#include "gate.h"
#include "worker.h"
namespace molly_denoise {
// One instance per WebRTC AudioTransport, bound to its creating factory. The
// shared group factory can begin a new owner; an old factory cannot adopt it.
class CallTransport {
 public:
  class Lease {
   public:
    Lease()=default;
    ~Lease(){Release();}
    Lease(Lease&& other)noexcept:gate_(std::move(other.gate_)),busy_(other.busy_){other.busy_=nullptr;}
    Lease& operator=(Lease&&)=delete;
    explicit operator bool()const noexcept{return busy_&&static_cast<bool>(gate_);}
   private:
    friend class CallTransport;
    Lease(CallGate::Lease gate,std::atomic_flag& busy):gate_(std::move(gate)),busy_(&busy){}
    void Release()noexcept {if(busy_)busy_->clear();}
    CallGate::Lease gate_;
    std::atomic_flag* busy_=nullptr;
  };
  CallTransport(DenoiseControl&,EngineFactory&,uint64_t factory_token,bool threaded=true);
  CallGate& Gate()noexcept{return gate_;}
  uint64_t FactoryToken()const noexcept{return factory_token_;}
  CaptureStamp StampCapture(uint32_t rate,uint16_t channels,uint16_t frames)noexcept;
  Lease PrepareSent(const CaptureStamp&,OriginalPacket&)noexcept;
  Lease PrepareReceived(OriginalPacket&)noexcept;
  ProcessResult ProcessSent(const OriginalPacket& p,std::span<float> out)noexcept{return sent_.Process(p,out);}
  ProcessResult ProcessReceived(const OriginalPacket& p,std::span<float> out)noexcept{return received_.Process(p,out);}
  DenoiseStatus Status(Direction)const noexcept;
  void PumpForTest(){sent_.PumpForTest();received_.PumpForTest();}
 private:
  static void Invalidate(void*,bool receive)noexcept;
  CallGate gate_;
  uint64_t factory_token_;
  CaptureClock capture_,render_;
  std::atomic_flag send_busy_=ATOMIC_FLAG_INIT,receive_busy_=ATOMIC_FLAG_INIT;
  DirectionProcessor sent_,received_;
};
}
#endif
