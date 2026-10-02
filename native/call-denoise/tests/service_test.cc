/* SPDX-License-Identifier: AGPL-3.0-only */
#include "service.h"
#include <cassert>
#include <iostream>
using namespace molly_denoise;
struct EmptyFactory:EngineFactory{std::unique_ptr<StreamingEngine>Create(Model,uint32_t,const DfConfig&)override{return {};}};
void creation_is_verified(){EmptyFactory f;NativeService s(f);auto id=s.BeginFactory();assert(id);assert(!s.BeginFactory());TransportBinding t(s,false);assert(!t.Bound());assert(s.FinishFactory(id));assert(t.Bound());assert(t.Core().FactoryToken()==id);}
void late_factory_construction_fails_open(){EmptyFactory f;NativeService s(f);auto id=s.BeginFactory();assert(!s.FinishFactory(id));TransportBinding too_late(s,false);assert(!too_late.Bound());assert(!s.BeginSession(s.NewOwner(),id));}
void duplicate_binding_is_rejected(){EmptyFactory f;NativeService s(f);auto id=s.BeginFactory();TransportBinding a(s,false),b(s,false);assert(!s.FinishFactory(id));assert(!a.Bound()&&!b.Bound());}
void old_factory_never_takes_new_session(){EmptyFactory f;NativeService s(f);auto a=s.BeginFactory();TransportBinding old(s,false);assert(s.FinishFactory(a));auto first=s.NewOwner();assert(s.BeginSession(first,a));assert(s.SetGate(first,true,GateReason::Mute));auto stale=old.Core().StampCapture(48000,1,480);auto b=s.BeginFactory();TransportBinding current(s,false);s.FinishFactory(b);auto next=s.NewOwner();assert(s.BeginSession(next,b));assert(!old.Core().Gate().Read().active);assert(s.SetGate(next,true,GateReason::Mute));assert(!s.SetGate(first,false,GateReason::Mute));assert(!s.EndSession(first));assert(!s.BeginSession(first,a));OriginalPacket p;p.meta.rate=48000;p.meta.frames=480;p.meta.channels=1;assert(!old.Core().PrepareSent(stale,p));assert(!current.Core().PrepareSent(stale,p));}
void shared_group_factory_new_owner(){EmptyFactory f;NativeService s(f);auto factory=s.BeginFactory();TransportBinding t(s,false);assert(s.FinishFactory(factory));auto a=s.NewOwner();assert(s.BeginSession(a,factory));s.SetGate(a,true,GateReason::Mute);auto stamp=t.Core().StampCapture(48000,1,480);s.EndSession(a);auto b=s.NewOwner();assert(s.BeginSession(b,factory));s.SetGate(b,true,GateReason::Mute);OriginalPacket p;p.meta.rate=48000;p.meta.frames=480;p.meta.channels=1;assert(!t.Core().PrepareSent(stamp,p));}
int main(){
#define RUN(t) t();std::cout<<#t<<" PASS\n";
RUN(creation_is_verified);RUN(late_factory_construction_fails_open);RUN(duplicate_binding_is_rejected);RUN(old_factory_never_takes_new_session);RUN(shared_group_factory_new_owner);
}
