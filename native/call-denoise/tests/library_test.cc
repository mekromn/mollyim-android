/* SPDX-License-Identifier: AGPL-3.0-only */
#include "worker.h"
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
using namespace molly_denoise;
std::vector<float> Read(const std::filesystem::path& p){
  std::ifstream file(p,std::ios::binary|std::ios::ate);assert(file);auto size=file.tellg();assert(size>0&&size%4==0);
  std::vector<float> out(static_cast<size_t>(size)/4);file.seekg(0);assert(file.read(reinterpret_cast<char*>(out.data()),size));return out;
}
int main(){
  std::cout.setf(std::ios::unitbuf);
  const char* lib=std::getenv("DF_HOST_RUNTIME");const char* root=std::getenv("DF_REFERENCE_ROOT");assert(lib&&root);
  std::filesystem::path dir(root);
  const std::array<std::string,2> paths{(dir/"models/DeepFilterNet3_onnx.tar.gz").string(),(dir/"models/DeepFilterNet3_ll_onnx.tar.gz").string()};
  RuntimeFactory missing("/nonexistent/runtime.so",paths);assert(!missing.Create(Model::Standard,0,DenoiseConfig{}.parameters));
  RuntimeFactory factory(lib,paths);assert(!factory.Create(static_cast<Model>(99),0,DenoiseConfig{}.parameters));
  const auto temp=std::filesystem::temp_directory_path()/"molly-df-worker-retry-fixture";
  std::filesystem::create_directories(temp);const auto model_file=temp/"model.tar.gz";
  {std::ofstream file(model_file,std::ios::binary);file<<"corrupt fixture";}
  RuntimeFactory repaired(lib,{model_file.string(),paths[1]});
  assert(!repaired.Create(Model::Standard,0,DenoiseConfig{}.parameters));
  std::filesystem::copy_file(paths[0],model_file,std::filesystem::copy_options::overwrite_existing);
  assert(repaired.Create(Model::Standard,0,DenoiseConfig{}.parameters));
  std::filesystem::remove_all(temp);
  std::cout<<"Explicit retry after repaired asset PASS\n";
  const auto input=Read(dir/"vectors/input.f32");
  for(Model model:{Model::Standard,Model::LowLatency}){
    const auto golden=Read(dir/(model==Model::Standard?"vectors/standard.f32":"vectors/low_latency.f32"));
    auto left=factory.Create(model,0,DenoiseConfig{}.parameters);auto right=factory.Create(model,1,DenoiseConfig{}.parameters);assert(left&&right);
    assert(left->Metadata().intrinsic_delay==(model==Model::Standard?1440u:480u));
    for(int repetition=0;repetition<2;++repetition){
      if(repetition){assert(left->Reset());assert(right->Reset());}
      float error=0;std::array<float,480> output{},silent{},silence{};float snr=0;
      for(size_t offset=0;offset<input.size();offset+=480){
        assert(left->Process(std::span<const float>(input).subspan(offset,480),output,snr));assert(std::isfinite(snr));
        assert(right->Process(silence,silent,snr));for(float v:silent)assert(std::abs(v)<1e-8f);
        for(size_t i=0;i<480;++i)error=std::max(error,std::abs(output[i]-golden[offset+i]));
      }
      std::cout<<"RuntimeFactory model="<<static_cast<int>(model)<<" repetition="<<repetition<<" reference max error="<<error<<"\n";assert(error<=1e-5f);
    }
    left.reset();right.reset();
    // Real worker code, deterministically pumped; independence uses separate
    // model contexts and does not turn timing tests into device benchmarks.
    DenoiseControl c;DenoiseConfig config;config.enabled=true;config.model=model;c.Update(Direction::Received,config);
    DirectionProcessor worker(c,Direction::Received,factory,false);std::array<float,kMaxSamples> out{};size_t wet=0;float error=0;
    const size_t model_hops=model==Model::Standard?3:1;
    for(size_t block=0;block<input.size()/480;++block){
      OriginalPacket p;p.meta={1,1,static_cast<int64_t>(block*480),block,1000000000LL+static_cast<int64_t>(block)*10000000LL,0,0,48000,1,480};
      for(size_t i=0;i<480;++i)p.pcm[i]=static_cast<int16_t>(std::lrint(input[block*480+i]*32768));
      const auto result=worker.Process(p,out);worker.PumpForTest();
      if(result.kind==OutputKind::Wet&&block>model_hops+3){
        ++wet;const auto golden_at=static_cast<size_t>(result.source.source_start)+model_hops*480;
        for(size_t i=0;i<480;++i)error=std::max(error,std::abs(out[i]-golden[golden_at+i]));
      }
    }
    assert(wet>80&&error<1e-5f);std::cout<<"Direction worker model="<<static_cast<int>(model)<<" wet blocks="<<wet<<" reference max error="<<error<<" PASS\n";
  }
}
