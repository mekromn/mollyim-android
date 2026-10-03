/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_SERVICE_H_
#define MOLLY_DENOISE_SERVICE_H_
#include "transport.h"
#include "audio/molly_incoming/effect_scope.h"
#include <vector>
#include <semaphore>
namespace molly_denoise {
class TransportBinding;
// All registry operations are control/construction/destruction only. Audio has
// a stable direct reference to its own CallTransport; it never takes this mutex.
class NativeService {
 public:
  explicit NativeService(EngineFactory& factory,DenoiseControl* controls=nullptr,molly_audio::EffectScope* effects=nullptr,bool local=false)
      :factory_(factory),external_controls_(controls),external_effects_(effects),local_(local){}
  uint64_t BeginFactory()noexcept;
  bool FinishFactory(uint64_t)noexcept;
  uint64_t CreationToken()const noexcept{return creating_.load();}
  uint64_t NewOwner()noexcept;
  bool HasActiveSession()noexcept;
  bool BeginSession(uint64_t owner,uint64_t factory)noexcept;
  bool EndSession(uint64_t owner)noexcept;
  bool SetGate(uint64_t owner,bool allowed,GateReason reason)noexcept;
  bool Invalidate(uint64_t owner,GateReason reason)noexcept;
  DenoiseStatus Status(Direction)noexcept;
  DenoiseControl& Controls()noexcept{return external_controls_?*external_controls_:control_;}
  molly_audio::EffectScope& Effects()noexcept{return external_effects_?*external_effects_:molly_audio::ProductionEffectScope();}
  bool Local()const noexcept{return local_;}
  EngineFactory& Factory()noexcept{return factory_;}
 private:
  friend class TransportBinding;
  void Attach(TransportBinding*);
  void Detach(TransportBinding*);
  TransportBinding* Active()noexcept;
  std::mutex mutex_;
  std::vector<TransportBinding*> transports_;
  std::atomic<uint64_t> creating_{0},next_{1};
  uint64_t highest_owner_=0,active_owner_=0,active_factory_=0;
  EngineFactory& factory_;
  DenoiseControl control_;
  DenoiseControl* external_controls_;
  molly_audio::EffectScope* external_effects_;
  bool local_;
};
class TransportBinding {
 public:
  explicit TransportBinding(NativeService& service,bool threaded=true);
  ~TransportBinding();
  bool Bound()const noexcept{return verified_.load();}
  CallTransport& Core()noexcept{return transport_;}
  const CallTransport& Core()const noexcept{return transport_;}
  NativeService& Service()noexcept{return service_;}
 private:
  friend class NativeService;
  NativeService& service_;
  std::atomic<bool> verified_{false};
  CallTransport transport_;
};
// Paths may be installed after native initialization. Only workers touch the
// dynamic runtime; a disabled settings edit never loads or instantiates it.
class ConfigurableFactory final:public EngineFactory {
 public:
  bool SetPaths(std::string library,std::array<std::string,3> models);
  std::unique_ptr<StreamingEngine>Create(Model,uint32_t,const DfConfig&)override;
 private:
  std::mutex mutex_;
  std::shared_ptr<RuntimeFactory> factory_;
  std::string library_;
  std::array<std::string,3> models_;
  // Old and replacement factories cannot temporarily create >4 contexts.
  // Waiting for a permit is worker-only and bounded; callbacks keep dry audio.
  std::counting_semaphore<4> budget_{4};
};
ConfigurableFactory& GlobalFactory();
NativeService& GlobalService();
// Selection is construction-only under the shared Java factory lock. A verified
// instance retains its own service; callbacks never consult this selector.
NativeService& ConstructionService();
bool SelectConstructionService(NativeService*)noexcept;
bool ClearConstructionService(NativeService*)noexcept;
}
#endif
