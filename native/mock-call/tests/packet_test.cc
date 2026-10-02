/* SPDX-License-Identifier: AGPL-3.0-only */
#include "packet.h"
#include <cassert>
#include <cmath>
#include <limits>
#include <thread>
#include <cstdio>
using namespace molly_mock;
int main(){
  TapPacket p;p.epoch=1;p.segment=1;p.rate=48000;p.channels=2;p.frames=480;
  assert(p.Valid());assert(p.Count()==960);p.frames=481;assert(!p.Valid());
  p.frames=480;p.rate=44100;assert(!p.Valid());p.rate=48000;
  p.tap=Tap::BackendInput;p.rate=192000;p.frames=1920;assert(p.Valid());
  p.frames=1921;assert(!p.Valid());p.frames=1920;p.channels=3;assert(!p.Valid());
  TapQueue queue; p.channels=2;
  for(unsigned i=0;i<32;++i){p.source_start=i*1920;assert(queue.Push(p));}
  assert(!queue.Push(p));TapPacket got;for(unsigned i=0;i<32;++i){assert(queue.Pop(got));assert(got.source_start==i*1920);}
  assert(!queue.Pop(got));
  std::thread producer([&]{for(int i=0;i<10000;++i){p.source_start=i;while(!queue.Push(p))std::this_thread::yield();}});
  for(int i=0;i<10000;++i){while(!queue.Pop(got))std::this_thread::yield();assert(got.source_start==i);}
  producer.join();
  Clip clip;assert(!clip.Assign(48000,3,{}));std::vector<int16_t> audio(48000,15);assert(clip.Assign(48000,1,std::move(audio)));
  std::array<int16_t,960> out{};assert(clip.Read(0,480,out));assert(out[479]==15);
  assert(clip.Read(-480,480,out));assert(out[0]==0);assert(clip.Read(47900,480,out));assert(out[99]==15&&out[100]==0);
  assert(!clip.Read(std::numeric_limits<int64_t>::max(),480,out));
  assert(!clip.Assign(8000,1,std::vector<int16_t>(8000*121)));
  std::puts("packet geometry, bounded queue, ownership, clip limits pass");
}
