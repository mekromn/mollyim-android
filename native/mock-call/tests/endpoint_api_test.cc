/* SPDX-License-Identifier: AGPL-3.0-only */
#include "audio/audio_transport_impl.h"
#include "endpoint.h"
#include <cassert>
#include <cstdio>
#include <condition_variable>
#include <future>
#include <chrono>
struct Adm:webrtc::AudioDeviceModule {
 int inputs=0,outputs=0;bool recording=false,playing=false,fail_stop=false;
 std::mutex mutex;std::condition_variable cv;bool block_stop=false,entered=false,release=false;
 int InitPlayout()override{return 0;}int StartPlayout()override{++outputs;playing=true;return 0;}int StopPlayout()override{if(fail_stop)return -1;playing=false;return 0;}
 int InitRecording()override{return 0;}int StartRecording()override{++inputs;recording=true;return 0;}int StopRecording()override{{std::unique_lock lock(mutex);if(block_stop){entered=true;cv.notify_all();cv.wait(lock,[&]{return release;});}}if(fail_stop)return -1;recording=false;return 0;}
};
struct Mixer:webrtc::AudioMixer {Source*source=nullptr;bool AddSource(Source*s)override{if(source)return false;source=s;return true;}void RemoveSource(Source*s)override{assert(s==source);source=nullptr;}};
int main(){
 using namespace molly_mock;
 webrtc::TaskQueueBase queue;queue.current=&queue;
 const auto id=PrepareSession();assert(id);assert(!PrepareSession());auto s=FindSession(id);
 {
  webrtc::AudioTransportImpl transport;Mixer mixer;Adm adm;
  {
   LabEndpoint endpoint(s,transport,mixer,&adm,&queue);transport.mock=&endpoint;transport.denoiser.SetObserver(&endpoint);
   assert(FinishSession(id));assert(endpoint.Attached());assert(!ReleaseSession(id));
   assert(LoadSession(id,0,48000,1,std::vector<int16_t>(4800,1234)));queue.Pump();
   assert(CommandSession(id,6,0,50,0,0));queue.Pump();
   assert(CommandSession(id,1,0,0,0,16));queue.Pump();
   assert(adm.playing&&!adm.recording&&adm.inputs==0);
   webrtc::AudioFrame frame;bool heard=false;int last=0;
   for(int i=0;i<115;++i){mixer.source->GetAudioFrameWithInfo(48000,&frame);transport.Render(&frame);last=frame.data_view()[0];heard|=last==1234;}
   assert(heard);assert(s->finished.load());
   assert(CommandSession(id,2,0,0,0,0));queue.Pump();assert(s->hardware_stopped.load());
   // Sent-only replay enters the same sent processor and never opens capture.
   assert(LoadSession(id,1,48000,1,std::vector<int16_t>(4800,2345)));queue.Pump();
   assert(CommandSession(id,1,1,0,1,0));queue.Pump();
   heard=false;for(int i=0;i<115;++i){mixer.source->GetAudioFrameWithInfo(48000,&frame);transport.Render(&frame);heard|=frame.data_view()[0]==2345;}
   assert(heard&&adm.inputs==0);
   assert(CommandSession(id,2,0,0,0,0));queue.Pump();
   // An explicit microphone take uses the same ADM; live feedback is denied.
   assert(CommandSession(id,1,1,1,0,6));queue.Pump();assert(adm.recording&&adm.inputs==1);
   assert(CommandSession(id,2,0,0,0,0));queue.Pump();
   assert(CommandSession(id,1,1,1,1,6));queue.Pump();assert(!adm.recording&&s->status.load()==5);
   assert(CommandSession(id,1,1,1,0,6));queue.Pump();assert(adm.recording);
   adm.fail_stop=true;
   assert(CommandSession(id,2,0,0,0,0));queue.Pump();
   if(s->hardware_stopped.load()){std::fputs("failed driver stop was acknowledged as released hardware\n",stderr);std::abort();}
   adm.fail_stop=false;
   assert(CommandSession(id,2,0,0,0,0));queue.Pump();assert(s->hardware_stopped.load());
   assert(CommandSession(id,1,1,1,0,6));queue.Pump();assert(adm.recording);
   adm.block_stop=true;
   assert(CommandSession(id,2,0,0,0,0));
   std::thread stopper([&]{queue.Pump();});
   {std::unique_lock lock(adm.mutex);adm.cv.wait(lock,[&]{return adm.entered;});}
   auto revoked=std::async(std::launch::async,[&]{RevokeSession(id);});
   if(revoked.wait_for(std::chrono::milliseconds(100))!=std::future_status::ready){std::fputs("revoke waited for a hardware-stop command lock\n",stderr);std::abort();}
   {std::lock_guard lock(adm.mutex);adm.release=true;adm.cv.notify_all();}
   stopper.join();revoked.get();queue.Pump();assert(s->hardware_stopped.load());assert(!CommandSession(id,1,0,0,0,0));
   transport.denoiser.SetObserver(nullptr);transport.mock=nullptr;
  }
 }
 assert(ReleaseSession(id));assert(!FindSession(id));
 std::puts("endpoint API fixture: no implicit capture, paced replay, local sink, lifecycle, no live speaker feedback pass");
}
