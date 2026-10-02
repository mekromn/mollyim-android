/* SPDX-License-Identifier: AGPL-3.0-only */
#include "service.h"
#include <algorithm>
#include <chrono>
#include <limits>
namespace molly_denoise {
bool NativeService::HasActiveSession()noexcept {std::lock_guard lock(mutex_);return active_owner_!=0;}
uint64_t NativeService::NewOwner()noexcept {
  const auto n=next_.fetch_add(1);return n&&n<std::numeric_limits<int64_t>::max()?n:0;
}
uint64_t NativeService::BeginFactory()noexcept {
  std::lock_guard lock(mutex_);if(creating_.load())return 0;
  const auto token=NewOwner();creating_.store(token);return token;
}
bool NativeService::FinishFactory(uint64_t factory)noexcept {
  std::lock_guard lock(mutex_);if(!factory||creating_.load()!=factory)return false;creating_.store(0);
  size_t count=0;for(auto* t:transports_)if(t->transport_.FactoryToken()==factory)++count;
  for(auto* t:transports_)if(t->transport_.FactoryToken()==factory)t->verified_.store(count==1);
  return count==1;
}
void NativeService::Attach(TransportBinding* transport){std::lock_guard lock(mutex_);transports_.push_back(transport);}
void NativeService::Detach(TransportBinding* t){
  std::lock_guard lock(mutex_);const auto owner=t->transport_.Gate().Read().owner;t->transport_.Gate().EndSession(owner);
  if(owner==active_owner_){active_owner_=0;active_factory_=0;}
  transports_.erase(std::remove(transports_.begin(),transports_.end(),t),transports_.end());
}
TransportBinding* NativeService::Active()noexcept {
  for(auto* t:transports_)if(t->Bound()&&t->transport_.FactoryToken()==active_factory_)return t;
  return nullptr;
}
bool NativeService::BeginSession(uint64_t owner,uint64_t factory)noexcept {
  std::lock_guard lock(mutex_);if(!owner||owner>static_cast<uint64_t>(std::numeric_limits<int64_t>::max())||owner<=highest_owner_||!factory)return false;
  TransportBinding* selected=nullptr;for(auto* t:transports_)if(t->Bound()&&t->transport_.FactoryToken()==factory)selected=t;
  if(!selected)return false;
  for(auto* t:transports_){const auto old=t->transport_.Gate().Read();if(old.active)t->transport_.Gate().EndSession(old.owner);}
  if(!selected->transport_.Gate().BeginSession(owner))return false;
  Controls().Bypass(Direction::Received,false);Controls().Bypass(Direction::Sent,false);
  highest_owner_=owner;active_owner_=owner;active_factory_=factory;return true;
}
bool NativeService::EndSession(uint64_t owner)noexcept {
  std::lock_guard lock(mutex_);if(owner!=active_owner_||!owner)return false;auto* t=Active();
  const bool ok=t&&t->transport_.Gate().EndSession(owner);active_owner_=0;active_factory_=0;return ok;
}
bool NativeService::SetGate(uint64_t owner,bool allowed,GateReason reason)noexcept {
  std::lock_guard lock(mutex_);if(owner!=active_owner_||!owner)return false;auto* t=Active();return t&&t->transport_.Gate().SetGate(owner,allowed,reason);
}
bool NativeService::Invalidate(uint64_t owner,GateReason reason)noexcept {
  std::lock_guard lock(mutex_);if(owner!=active_owner_||!owner)return false;auto* t=Active();return t&&t->transport_.Gate().Invalidate(owner,reason);
}
DenoiseStatus NativeService::Status(Direction direction)noexcept {
  std::lock_guard lock(mutex_);if(auto* t=Active())return t->transport_.Status(direction);
  ControlSnapshot c;Controls().Read(direction,c);DenoiseStatus status;status.state=c.config.enabled?EffectiveState::Unavailable:EffectiveState::Off;return status;
}
TransportBinding::TransportBinding(NativeService& s,bool threaded):service_(s),transport_(s.Controls(),s.Factory(),s.CreationToken(),threaded){s.Attach(this);}
TransportBinding::~TransportBinding(){service_.Detach(this);}
bool ConfigurableFactory::SetPaths(std::string library,std::array<std::string,2> models){
  const auto valid=[](const std::string& path){return !path.empty()&&path.front()=='/'&&path.size()<4096;};
  if(!valid(library)||!valid(models[0])||!valid(models[1]))return false;
  std::lock_guard lock(mutex_);if(factory_&&library==library_&&models==models_)return true;
  library_=library;models_=models;factory_=std::make_shared<RuntimeFactory>(std::move(library),std::move(models));return true;
}
std::unique_ptr<StreamingEngine>ConfigurableFactory::Create(Model model,uint32_t channel,const DfConfig& config){
  std::shared_ptr<RuntimeFactory> factory;{std::lock_guard lock(mutex_);factory=factory_;}if(!factory)return {};
  if(!budget_.try_acquire_for(std::chrono::seconds(2)))return {};
  auto engine=factory->Create(model,channel,config);if(!engine){budget_.release();return {};}
  struct Limited final:StreamingEngine {
    std::unique_ptr<StreamingEngine> engine;std::counting_semaphore<4>& budget;
    Limited(std::unique_ptr<StreamingEngine> e,std::counting_semaphore<4>& b):engine(std::move(e)),budget(b){}
    ~Limited()override{engine.reset();budget.release();}
    DfMeta Metadata()const noexcept override{return engine->Metadata();}
    bool Configure(const DfConfig& c)noexcept override{return engine->Configure(c);}
    bool Reset()noexcept override{return engine->Reset();}
    bool Process(std::span<const float>in,std::span<float>out,float& snr)noexcept override{return engine->Process(in,out,snr);}
  };
  return std::make_unique<Limited>(std::move(engine),budget_);
}
ConfigurableFactory& GlobalFactory(){static auto* const factory=new ConfigurableFactory();return *factory;}
NativeService& GlobalService(){static auto* const service=new NativeService(GlobalFactory());return *service;}
namespace {std::atomic<NativeService*> constructing_service{nullptr};}
NativeService& ConstructionService(){auto* s=constructing_service.load();return s?*s:GlobalService();}
bool SelectConstructionService(NativeService* next)noexcept {
  if(!next||!next->Local())return false;
  NativeService* empty=nullptr;return constructing_service.compare_exchange_strong(empty,next);
}
bool ClearConstructionService(NativeService* expected)noexcept {
  return expected&&constructing_service.compare_exchange_strong(expected,nullptr);
}
}
