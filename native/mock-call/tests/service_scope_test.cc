/* SPDX-License-Identifier: AGPL-3.0-only */
#include "scope.h"
#include "audio/molly_denoise/service.h"
#include <cassert>
#include <iostream>
using namespace molly_denoise;
struct NoModels final:EngineFactory {
  int loads=0;
  std::unique_ptr<StreamingEngine>Create(Model,uint32_t,const DfConfig&)override {++loads;return {};}
};
int main(){
  NoModels factory; molly_mock::AudioScope scope;
  NativeService production(factory),lab(factory,&scope.denoise,&scope.effects,true);
  assert(!SelectConstructionService(&production));
  assert(SelectConstructionService(&lab));assert(&ConstructionService()==&lab);
  assert(!SelectConstructionService(&lab));assert(!ClearConstructionService(&production));
  const auto f=lab.BeginFactory(); assert(f);
  TransportBinding local(lab,false);assert(lab.FinishFactory(f));
  const auto p=production.BeginFactory();assert(p);
  TransportBinding real(production,false);assert(production.FinishFactory(p));
  assert(lab.BeginSession(lab.NewOwner(),f));assert(production.BeginSession(production.NewOwner(),p));
  DenoiseConfig enabled;enabled.enabled=true;
  assert(lab.Controls().Update(Direction::Sent,enabled));
  ControlSnapshot v;assert(production.Controls().Read(Direction::Sent,v)&&!v.config.enabled);
  assert(&local.Service().Effects()==&scope.effects);
  assert(&real.Service().Effects()!=&scope.effects);
  assert(factory.loads==0);assert(ClearConstructionService(&lab));assert(&ConstructionService()==&GlobalService());
  std::cout<<"PASS scoped native service binding, creation rejection, independent controls/meters and zero model loads\n";
}
