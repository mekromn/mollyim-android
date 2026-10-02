/* SPDX-License-Identifier: AGPL-3.0-only */
#include "library.h"
#include <dlfcn.h>
#include <fstream>
#include <vector>
namespace molly_denoise {
struct RuntimeFactory::Storage {
  std::mutex mutex;
  std::string library_path;
  std::array<std::string,2> model_paths;
  std::array<std::shared_ptr<const std::vector<uint8_t>>,2> models;
  void* library=nullptr;
  decltype(&molly_df_abi_version) version=nullptr;
  decltype(&molly_df_create) create=nullptr;
  decltype(&molly_df_configure) configure=nullptr;
  decltype(&molly_df_process) process=nullptr;
  decltype(&molly_df_reset) reset=nullptr;
  decltype(&molly_df_destroy) destroy=nullptr;
  ~Storage(){if(library)dlclose(library);}
  bool Load(){
    if(library)return true;
    void* candidate=dlopen(library_path.c_str(),RTLD_NOW|RTLD_LOCAL);if(!candidate)return false;
#define SYMBOL(name,member) member=reinterpret_cast<decltype(member)>(dlsym(candidate,name))
    SYMBOL("molly_df_abi_version",version);SYMBOL("molly_df_create",create);
    SYMBOL("molly_df_configure",configure);SYMBOL("molly_df_process",process);
    SYMBOL("molly_df_reset",reset);SYMBOL("molly_df_destroy",destroy);
#undef SYMBOL
    if(!version||!create||!configure||!process||!reset||!destroy||version()!=1){dlclose(candidate);return false;}
    library=candidate;return true;
  }
};
RuntimeFactory::RuntimeFactory(std::string path,std::array<std::string,2> models):storage_(std::make_shared<Storage>()){
  storage_->library_path=std::move(path);storage_->model_paths=std::move(models);
}
RuntimeFactory::~RuntimeFactory()=default;
std::unique_ptr<StreamingEngine> RuntimeFactory::Create(Model model,uint32_t channel,const DfConfig& config){
  if(static_cast<uint32_t>(model)>1||channel>1)return nullptr;
  DenoiseConfig checked;checked.model=model;checked.parameters=config;if(!checked.Valid())return nullptr;
  std::shared_ptr<const std::vector<uint8_t>> bytes;
  {
    std::lock_guard lock(storage_->mutex);if(!storage_->Load())return nullptr;
    auto& cached=storage_->models[static_cast<size_t>(model)];
    if(!cached){
      std::ifstream input(storage_->model_paths[static_cast<size_t>(model)],std::ios::binary|std::ios::ate);
      if(!input)return nullptr;
      const auto size=input.tellg();if(size<=0||size>40*1024*1024)return nullptr;
      auto data=std::make_shared<std::vector<uint8_t>>(static_cast<size_t>(size));input.seekg(0);
      if(!input.read(reinterpret_cast<char*>(data->data()),size))return nullptr;
      cached=std::move(data);
    }
    bytes=cached;
  }
  // The factory lock is not held during graph construction or inference.
  // A failed model does not serialize the other direction's running engine.
  const auto forget_failed_bytes=[&]{
    std::lock_guard lock(storage_->mutex);
    auto& cached=storage_->models[static_cast<size_t>(model)];
    if(cached==bytes)cached.reset();
  };
  DfHandle* handle=nullptr;DfMeta meta{};
  if(storage_->create(bytes->data(),bytes->size(),&config,&handle,&meta)!=0){forget_failed_bytes();return nullptr;}
  if(!handle||!DelayPlan::For(48000,1,meta)||meta.lookahead!=(model==Model::Standard?2u:0u)){
    if(handle)storage_->destroy(handle);
    forget_failed_bytes();return nullptr;
  }
  struct Engine final:StreamingEngine {
    std::shared_ptr<Storage> library;DfHandle* handle;DfMeta meta;
    Engine(std::shared_ptr<Storage> s,DfHandle* h,DfMeta m):library(std::move(s)),handle(h),meta(m){}
    ~Engine() override {library->destroy(handle);}
    DfMeta Metadata() const noexcept override{return meta;}
    bool Configure(const DfConfig& c) noexcept override{return library->configure(handle,&c)==0;}
    bool Reset() noexcept override{return library->reset(handle)==0;}
    bool Process(std::span<const float> in,std::span<float> out,float& snr) noexcept override{
      return library->process(handle,in.data(),in.size(),out.data(),out.size(),&snr)==0;
    }
  };
  return std::make_unique<Engine>(storage_,handle,meta);
}
}
