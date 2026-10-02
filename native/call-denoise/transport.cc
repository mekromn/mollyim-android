/* SPDX-License-Identifier: AGPL-3.0-only */
#include "transport.h"
namespace molly_denoise {
CallTransport::CallTransport(DenoiseControl& c,EngineFactory& f,uint64_t token,bool threaded)
 :gate_(this,&CallTransport::Invalidate),factory_token_(token),sent_(c,Direction::Sent,f,threaded),received_(c,Direction::Received,f,threaded){}
void CallTransport::Invalidate(void* self,bool receive)noexcept {
  auto& t=*static_cast<CallTransport*>(self);
  // The gate's exclusive hand-off lease excludes Process() while transferring
  // ownership briefly to clear callback history. Workers remain independently
  // cancellable and clear their own queues/state; never join here.
  t.sent_.QuiescentInvalidate();if(receive)t.received_.QuiescentInvalidate();
}
CaptureStamp CallTransport::StampCapture(uint32_t rate,uint16_t channels,uint16_t frames)noexcept{
  return capture_.Stamp(gate_.Read(),rate,channels,frames);
}
CallTransport::Lease CallTransport::PrepareSent(const CaptureStamp& stamp,OriginalPacket& p)noexcept{
  auto lease=gate_.Acquire(stamp.owner,stamp.gate_generation,Direction::Sent);
  if(!lease||!capture_.IsCurrent(stamp))return {};
  if(send_busy_.test_and_set())return {};
  p.meta.owner=stamp.owner;p.meta.generation=stamp.stream_generation;p.meta.source_start=stamp.source_start;
  if(!p.meta.frames){send_busy_.clear();return {};}
  p.meta.sequence=static_cast<uint64_t>(p.meta.source_start/p.meta.frames);
  return Lease(std::move(lease),send_busy_);
}
CallTransport::Lease CallTransport::PrepareReceived(OriginalPacket& p)noexcept{
  auto s=gate_.Read();auto lease=gate_.Acquire(s.owner,s.received_generation,Direction::Received);
  if(!lease)return {};
  if(receive_busy_.test_and_set())return {};
  s.sent_generation=s.received_generation;s.microphone=true;s.permission=true;
  const auto stamp=render_.Stamp(s,p.meta.rate,p.meta.channels,p.meta.frames);
  if(!stamp.Valid()){receive_busy_.clear();return {};}
  p.meta.owner=stamp.owner;p.meta.generation=stamp.stream_generation;p.meta.source_start=stamp.source_start;
  p.meta.sequence=static_cast<uint64_t>(p.meta.source_start/p.meta.frames);
  return Lease(std::move(lease),receive_busy_);
}
DenoiseStatus CallTransport::Status(Direction direction)const noexcept {
  auto s=direction==Direction::Sent?sent_.Status():received_.Status();const auto gate=gate_.Read();
  if(!gate.active){s.state=EffectiveState::Off;s.levels_valid=false;s.inference_valid=false;}
  else if(direction==Direction::Sent&&!gate.SendAllowed()){s.state=EffectiveState::Muted;s.levels_valid=false;s.inference_valid=false;}
  return s;
}
}
