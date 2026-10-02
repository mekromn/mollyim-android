/* SPDX-License-Identifier: AGPL-3.0-only */
#include "scope.h"
#include <cassert>
#include <thread>
#include <cmath>
#include <iostream>
using molly_denoise::Direction;
int main() {
  molly_mock::AudioScope production, lab;
  molly_denoise::DenoiseConfig received,sent;
  received.enabled=true; received.parameters.attenuation_db=12;
  sent.enabled=true; sent.parameters.attenuation_db=54;
  molly_audio::Settings effect; effect.enabled=true;effect.gain_db=7;
  assert(lab.Configure(received,sent,effect));
  molly_denoise::ControlSnapshot a,b;
  assert(production.denoise.Read(Direction::Received,a)&&!a.config.enabled);
  assert(lab.denoise.Read(Direction::Received,a)&&a.config.enabled&&a.config.parameters.attenuation_db==12);
  assert(lab.denoise.Read(Direction::Sent,b)&&b.config.parameters.attenuation_db==54);
  assert(a.revision==b.revision);
  molly_audio::Settings es;
  assert(production.effects.bus.Read(es)&&!es.enabled&&es.gain_db==0);
  assert(lab.effects.bus.Read(es)&&es.gain_db==7);
  lab.effects.reset_epoch.fetch_add(1);lab.effects.meter_values[0].store(5);lab.effects.frame_counter.fetch_add(1);
  assert(production.effects.reset_epoch.load()==0&&production.effects.meter_values[0].load()==0&&production.effects.frame_counter.load()==0);
  auto old=lab.Revision();received.parameters.attenuation_db=NAN;
  assert(!lab.Configure(received,sent,effect));assert(lab.Revision()==old);
  assert(lab.denoise.Read(Direction::Received,a)&&a.config.parameters.attenuation_db==12);
  assert(lab.denoise.UpdateBoth(sent,sent));
  std::atomic<bool> stop{false};
  std::thread writer([&]{for(int i=0;i<100000;++i){molly_denoise::DenoiseConfig x,y;x.parameters.attenuation_db=float(i%50);y.parameters.attenuation_db=float(i%50);assert(lab.denoise.UpdateBoth(x,y));}stop=true;});
  while(!stop){std::array<molly_denoise::ControlSnapshot,2> pair;if(lab.denoise.ReadBoth(pair))assert(pair[0].revision==pair[1].revision&&pair[0].config.parameters.attenuation_db==pair[1].config.parameters.attenuation_db);}
  writer.join();
  {molly_mock::AudioScope discarded;assert(discarded.Configure({}, {}, effect));}
  assert(production.effects.bus.Read(es)&&!es.enabled);
  std::cout<<"PASS native scope isolation, invalid transaction, separate meters and 100000 coherent paired updates\n";
}
