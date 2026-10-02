/* SPDX-License-Identifier: AGPL-3.0-only */
#include "gate.h"
#include <limits>
namespace molly_denoise {
GateSnapshot CallGate::Read()const noexcept {GateSnapshot s;snapshot_.Read(s);return s;}
CallGate::Lease CallGate::Acquire(uint64_t owner,uint64_t generation,Direction direction) noexcept {
  if(closing_.load())return {};
  Lease lease(handoff_);if(!lease||closing_.load())return {};
  const auto s=Read();
  if(!s.active||s.owner!=owner||!generation)return {};
  if(direction==Direction::Sent){if(!s.SendAllowed()||s.sent_generation!=generation)return {};}
  else if(direction==Direction::Received){if(s.received_generation!=generation)return {};}
  else return {};
  return lease;
}
bool CallGate::BeginSession(uint64_t owner)noexcept{return Change(owner,false,GateReason::End,true,false);}
bool CallGate::EndSession(uint64_t owner)noexcept{return Change(owner,false,GateReason::End,false,false);}
bool CallGate::SetGate(uint64_t owner,bool allowed,GateReason reason)noexcept{
  if(reason!=GateReason::Mute&&reason!=GateReason::RemoteMute&&reason!=GateReason::Permission)return false;
  return Change(owner,allowed,reason,false,false);
}
bool CallGate::Invalidate(uint64_t owner,GateReason reason)noexcept{
  if(reason!=GateReason::Route&&reason!=GateReason::Format&&reason!=GateReason::CaptureStop)return false;
  return Change(owner,false,reason,false,true);
}
bool CallGate::Change(uint64_t owner,bool allowed,GateReason reason,bool begin,bool invalidate)noexcept {
  if(!owner||owner>static_cast<uint64_t>(std::numeric_limits<int64_t>::max()))return false;
  std::lock_guard serialized(writer_);
  if(begin){if(owner<=current_.owner)return false;}
  else if(owner!=current_.owner||!current_.active)return false;
  if(!begin&&!invalidate&&reason!=GateReason::End){
    const bool previous=reason==GateReason::Permission?current_.permission:current_.microphone;
    if(previous==allowed)return true;
  }
  closing_.store(true);
  std::unique_lock delivery(handoff_);
  const bool receive=begin||reason==GateReason::End||reason==GateReason::Route;
  if(begin){current_.owner=owner;current_.active=true;current_.microphone=false;current_.permission=true;}
  else if(reason==GateReason::End){current_.active=false;current_.microphone=false;}
  else if(!invalidate){if(reason==GateReason::Permission)current_.permission=allowed;else current_.microphone=allowed;}
  if(current_.sent_generation==std::numeric_limits<uint64_t>::max()||current_.received_generation==std::numeric_limits<uint64_t>::max()){
    current_.active=false;current_.microphone=false;
  }else{++current_.sent_generation;if(receive)++current_.received_generation;}
  snapshot_.Publish(current_);
  if(invalidator_)invalidator_(target_,receive);
  closing_.store(false);
  return true;
}
CaptureStamp CaptureClock::Stamp(const GateSnapshot& gate,uint32_t rate,uint16_t channels,uint16_t frames)noexcept {
  PacketMeta now{gate.owner,gate.sent_generation,0,0,-1,-1,-1,rate,channels,frames};
  if(!gate.SendAllowed()||!rate||!channels||!frames){current_.store(0);previous_={};return {};}
  const bool fresh=!previous_.owner||previous_.owner!=now.owner||previous_.generation!=now.generation||previous_.rate!=rate||previous_.channels!=channels||previous_.frames!=frames;
  if(fresh){if(counter_==std::numeric_limits<uint64_t>::max()){current_.store(0);return {};}++counter_;}
  else{
    if(previous_.source_start>std::numeric_limits<int64_t>::max()-frames){current_.store(0);previous_={};return {};}
    now.source_start=previous_.source_start+frames;
  }
  previous_=now;current_.store(counter_);
  return {now.owner,now.generation,counter_,now.source_start};
}
}
