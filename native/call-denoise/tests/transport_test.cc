/* SPDX-License-Identifier: AGPL-3.0-only */
#include "gate.h"
#include "transport.h"
#include "worker.h"
#include <cassert>
#include <atomic>
#include <iostream>
#include <semaphore>
#include <thread>
using namespace molly_denoise;
void mute_during_inference(){
  CallGate g;assert(g.BeginSession(1));assert(g.SetGate(1,true,GateReason::Mute));
  auto old=g.Read();assert(old.SendAllowed());
  assert(g.SetGate(1,false,GateReason::Mute));
  assert(!g.Acquire(old.owner,old.sent_generation,Direction::Sent));
  assert(g.SetGate(1,true,GateReason::Mute));
  assert(!g.Acquire(old.owner,old.sent_generation,Direction::Sent));
  auto fresh=g.Read();assert(fresh.sent_generation!=old.sent_generation);
  assert(g.Acquire(fresh.owner,fresh.sent_generation,Direction::Sent));
}
void remote_admin_mute(){CallGate g;g.BeginSession(1);g.SetGate(1,true,GateReason::Mute);auto a=g.Read();g.SetGate(1,false,GateReason::RemoteMute);auto b=g.Read();assert(!b.SendAllowed());assert(b.received_generation==a.received_generation);assert(!g.Acquire(1,a.sent_generation,Direction::Sent));assert(g.Acquire(1,a.received_generation,Direction::Received));}
void stale_group_callback(){CallGate g;g.BeginSession(10);g.SetGate(10,true,GateReason::Mute);g.BeginSession(11);g.SetGate(11,true,GateReason::Mute);auto current=g.Read();assert(!g.SetGate(10,false,GateReason::RemoteMute));assert(!g.EndSession(10));assert(!g.BeginSession(10));auto actual=g.Read();assert(actual.owner==11&&actual.sent_generation==current.sent_generation&&actual.SendAllowed());}
void end_never_reopens(){CallGate g;g.BeginSession(1);g.SetGate(1,true,GateReason::Mute);auto a=g.Read();g.EndSession(1);assert(!g.SetGate(1,true,GateReason::Mute));assert(!g.BeginSession(1));assert(!g.Acquire(1,a.sent_generation,Direction::Sent));assert(g.BeginSession(2));assert(!g.Read().SendAllowed());}
void permission_route_format_reset(){CallGate g;g.BeginSession(1);g.SetGate(1,true,GateReason::Mute);g.SetGate(1,false,GateReason::Permission);g.SetGate(1,true,GateReason::Mute);assert(!g.Read().SendAllowed());g.SetGate(1,true,GateReason::Permission);auto a=g.Read();g.Invalidate(1,GateReason::Route);auto b=g.Read();assert(b.SendAllowed());assert(a.sent_generation!=b.sent_generation&&a.received_generation!=b.received_generation);g.Invalidate(1,GateReason::Format);auto c=g.Read();assert(c.sent_generation!=b.sent_generation);}
void concurrent_close_linearization(){
  CallGate g;g.BeginSession(1);g.SetGate(1,true,GateReason::Mute);auto a=g.Read();
  std::binary_semaphore held(0),release(0);std::atomic<bool> closed=false;
  std::thread sender([&]{auto lease=g.Acquire(1,a.sent_generation,Direction::Sent);assert(lease);held.release();release.acquire();assert(!closed.load());});
  held.acquire();std::thread muter([&]{g.SetGate(1,false,GateReason::Mute);closed=true;});
  while(!g.Closing())std::this_thread::yield();
  assert(!g.Acquire(1,a.sent_generation,Direction::Sent));
  release.release();sender.join();muter.join();assert(closed);assert(!g.Acquire(1,a.sent_generation,Direction::Sent));
}
void clock_stamps_before_async(){
  CallGate g;g.BeginSession(1);g.SetGate(1,true,GateReason::Mute);CaptureClock clock;
  auto a=clock.Stamp(g.Read(),48000,1,480);assert(a.Valid());
  g.SetGate(1,false,GateReason::Mute);g.SetGate(1,true,GateReason::Mute);
  auto b=clock.Stamp(g.Read(),48000,1,480);assert(b.Valid()&&b.stream_generation!=a.stream_generation);
  assert(!clock.IsCurrent(a));assert(clock.IsCurrent(b));
  assert(!g.Acquire(a.owner,a.gate_generation,Direction::Sent));
  auto c=clock.Stamp(g.Read(),16000,2,160);assert(c.Valid());assert(!clock.IsCurrent(b));assert(c.source_start==0);
  auto invalid=clock.Stamp(g.Read(),44100,1,441);assert(invalid.Valid());assert(!clock.IsCurrent(c));
}
void gate_snapshots_coherent(){CallGate g;g.BeginSession(1);std::atomic<bool> done=false;std::thread writer([&]{for(int i=0;i<20000;++i)g.SetGate(1,i%2==0,GateReason::Mute);done=true;});while(!done){auto s=g.Read();if(s.owner){assert(s.owner==1);assert(s.sent_generation>0);}}writer.join();}

struct UnavailableFactory final:EngineFactory {
  int loads=0;
  std::unique_ptr<StreamingEngine> Create(Model,uint32_t,const DfConfig&)override{++loads;return {};}
};
OriginalPacket NativePacket(int n,int16_t sentinel=1000){OriginalPacket p;p.meta.rate=48000;p.meta.frames=480;p.meta.channels=1;p.meta.capture_time_ns=1000000000LL+10000000LL*n;std::fill(p.pcm.begin(),p.pcm.end(),sentinel);return p;}
void transport_unmute_never_replays(){
  DenoiseControl c;UnavailableFactory factory;CallTransport t(c,factory,1,false);assert(t.Gate().BeginSession(1));t.Gate().SetGate(1,true,GateReason::Mute);
  DenoiseConfig enabled;enabled.enabled=true;c.Update(Direction::Sent,enabled);
  std::array<float,kMaxSamples> out{};CaptureStamp old;
  for(int n=0;n<12;++n){auto p=NativePacket(n,1111);old=t.StampCapture(48000,1,480);auto lease=t.PrepareSent(old,p);assert(lease);t.ProcessSent(p,out);t.PumpForTest();}
  t.Gate().SetGate(1,false,GateReason::RemoteMute);assert(t.Status(Direction::Sent).state==EffectiveState::Muted);
  t.Gate().SetGate(1,true,GateReason::Mute);
  auto late=NativePacket(13,1111);assert(!t.PrepareSent(old,late));
  for(int n=14;n<26;++n){auto p=NativePacket(n,2222);auto stamp=t.StampCapture(48000,1,480);auto lease=t.PrepareSent(stamp,p);assert(lease);auto r=t.ProcessSent(p,out);t.PumpForTest();assert(r.source.generation!=old.stream_generation);for(size_t i=0;i<480;++i)assert(out[i]==0||out[i]==2222/32768.f);}
}
void receive_keeps_working_while_muted(){DenoiseControl c;UnavailableFactory f;CallTransport t(c,f,1,false);t.Gate().BeginSession(1);auto p=NativePacket(0,321);std::array<float,kMaxSamples> out{};{auto rx=t.PrepareReceived(p);assert(rx);auto r=t.ProcessReceived(p,out);assert(r.kind==OutputKind::Direct&&out[0]==321/32768.f);}auto stamp=t.StampCapture(48000,1,480);assert(!stamp.Valid());assert(!t.PrepareSent(stamp,p));}
void two_transports_do_not_retag_old_audio(){DenoiseControl c;UnavailableFactory f;CallTransport old(c,f,1,false),fresh(c,f,2,false);old.Gate().BeginSession(1);old.Gate().SetGate(1,true,GateReason::Mute);auto stamp=old.StampCapture(48000,1,480);old.Gate().EndSession(1);fresh.Gate().BeginSession(2);fresh.Gate().SetGate(2,true,GateReason::Mute);auto p=NativePacket(0);assert(!old.PrepareSent(stamp,p));assert(!fresh.PrepareSent(stamp,p));}
void unsupported_format_preserves_ordinary_send(){DenoiseControl c;UnavailableFactory f;CallTransport t(c,f,1,false);t.Gate().BeginSession(1);t.Gate().SetGate(1,true,GateReason::Mute);OriginalPacket p;p.meta.rate=44100;p.meta.channels=1;p.meta.frames=441;auto stamp=t.StampCapture(44100,1,441);auto lease=t.PrepareSent(stamp,p);assert(lease);std::array<float,kMaxSamples> out{};auto result=t.ProcessSent(p,out);assert(result.kind==OutputKind::Direct&&result.state==EffectiveState::Unsupported);assert(f.loads==0);}
void callback_guard_is_nonblocking(){DenoiseControl c;UnavailableFactory f;CallTransport t(c,f,1,false);t.Gate().BeginSession(1);auto a=NativePacket(0),b=NativePacket(1);auto lease=t.PrepareReceived(a);assert(lease);assert(!t.PrepareReceived(b));}

int main(){std::cout.setf(std::ios::unitbuf);
#define RUN(x) x();std::cout<<#x<<" PASS\n";
RUN(mute_during_inference);RUN(remote_admin_mute);RUN(stale_group_callback);RUN(end_never_reopens);RUN(permission_route_format_reset);RUN(concurrent_close_linearization);RUN(clock_stamps_before_async);RUN(gate_snapshots_coherent);RUN(transport_unmute_never_replays);RUN(receive_keeps_working_while_muted);RUN(two_transports_do_not_retag_old_audio);RUN(callback_guard_is_nonblocking);RUN(unsupported_format_preserves_ordinary_send);
}
