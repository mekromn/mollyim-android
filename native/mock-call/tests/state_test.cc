/* SPDX-License-Identifier: AGPL-3.0-only */
#include "state.h"
#include <cassert>
#include <thread>
#include <cstdio>
using namespace molly_mock;
int main(){
 LabState s;const auto initial=s.Epoch();assert(!s.Allowed(initial));
 assert(s.Start(1,3));const auto e=s.Epoch();assert(e!=initial&&s.Allowed(e));
 TapPacket p;p.epoch=e;p.segment=1;p.rate=48000;p.frames=480;p.channels=1;p.tap=Tap::BeforeSent;p.pcm[0]=.25f;
 assert(s.Observe(p));TapPacket out;assert(s.Drain(Tap::BeforeSent,out));assert(out.pcm[0]==.25f);
 s.Mute(true);assert(!s.Observe(p));s.Mute(false);assert(!s.Observe(p));
 p.epoch=s.Epoch();assert(s.Observe(p));
 s.Stop();assert(!s.Allowed(p.epoch));assert(!s.Drain(Tap::BeforeSent,out));
 assert(s.Start(2,3));p.epoch=s.Epoch();p.segment=2;
 for(unsigned i=0;i<32;++i){assert(s.Observe(p));}assert(!s.Observe(p));assert(s.Dropped()==1);assert(s.RecordingIncomplete());
 s.Revoke();assert(!s.Start(3,3));assert(!s.Observe(p));assert(!s.Drain(Tap::BeforeSent,out));
 std::puts("state: epoch, record gate, mute, revoke and full-queue tests pass");
}
