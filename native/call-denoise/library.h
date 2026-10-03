/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_LIBRARY_H_
#define MOLLY_DENOISE_LIBRARY_H_
#include "control.h"
#include "timeline.h"
#include <memory>
#include <string>
namespace molly_denoise {
class StreamingEngine:public HopProcessor {
 public:
  virtual DfMeta Metadata() const noexcept=0;
  virtual bool Configure(const DfConfig&) noexcept=0;
  virtual bool Reset() noexcept=0;
};
class EngineFactory {
 public:
  virtual ~EngineFactory()=default;
  // Invoked only by a direction's worker. Immutable graph bytes may be shared;
  // every returned engine owns independent mutable history.
  virtual std::unique_ptr<StreamingEngine> Create(Model,uint32_t channel,const DfConfig&)=0;
};
class RuntimeFactory final:public EngineFactory {
 public:
  // Caller supplies application-private verified paths, never audio URIs.
  RuntimeFactory(std::string library_path,std::array<std::string,3> model_paths);
  ~RuntimeFactory() override;
  std::unique_ptr<StreamingEngine> Create(Model,uint32_t,const DfConfig&) override;
 private:
  struct Storage;
  std::shared_ptr<Storage> storage_;
};
}
#endif
