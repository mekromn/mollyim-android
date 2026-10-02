/* SPDX-License-Identifier: AGPL-3.0-only */
// Actual pinned Rust inference + actual pinned WebRTC converters. No call audio.
#include "timeline.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
using namespace molly_denoise;
static std::vector<uint8_t> Read(const std::filesystem::path& path){
  std::ifstream file(path,std::ios::binary);assert(file);return {std::istreambuf_iterator<char>(file),{}};
}
struct Api {
  void* library=nullptr;
  decltype(&molly_df_create) create=nullptr;decltype(&molly_df_process) process=nullptr;decltype(&molly_df_destroy) destroy=nullptr;
  Api(){
    auto path=std::getenv("DF_HOST_RUNTIME");assert(path);library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(!library){std::cerr<<dlerror()<<"\n";std::abort();}
    create=reinterpret_cast<decltype(create)>(dlsym(library,"molly_df_create"));
    process=reinterpret_cast<decltype(process)>(dlsym(library,"molly_df_process"));
    destroy=reinterpret_cast<decltype(destroy)>(dlsym(library,"molly_df_destroy"));assert(create&&process&&destroy);
  }
  ~Api(){dlclose(library);}
};
class NativeHop final:public HopProcessor {
 public:
  NativeHop(Api& api,std::span<const uint8_t> bytes,float attenuation,bool transparent_stages=false):api_(api){
    DfConfig config{1,attenuation,0,0.02f,-10,30,20,0};
    if(transparent_stages){config.min_snr=-30;config.erb_snr=-30;config.df_snr=-30;}
    assert(api_.create(bytes.data(),bytes.size(),&config,&handle_,&meta)==0);assert(handle_);
  }
  ~NativeHop(){api_.destroy(handle_);}
  bool Process(std::span<const float> in,std::span<float> out,float& snr) noexcept override {
    return api_.process(handle_,in.data(),in.size(),out.data(),out.size(),&snr)==0;
  }
  DfMeta meta{};
 private:Api& api_;DfHandle* handle_=nullptr;
};
int main(){
  Api api;const auto root=std::getenv("DF_REFERENCE_ROOT");assert(root);std::filesystem::path dir(root);
  const auto input_bytes=Read(dir/"vectors/input.f32");assert(input_bytes.size()==48000*sizeof(float));
  std::array<float,48000> input{};std::memcpy(input.data(),input_bytes.data(),input_bytes.size());
  for(bool ll:{false,true}){
    const auto bytes=Read(dir/"models"/(ll?"DeepFilterNet3_ll_onnx.tar.gz":"DeepFilterNet3_onnx.tar.gz"));
    const auto golden_bytes=Read(dir/"vectors"/(ll?"low_latency.f32":"standard.f32"));assert(golden_bytes.size()==input_bytes.size());
    std::array<float,48000> golden{};std::memcpy(golden.data(),golden_bytes.data(),golden_bytes.size());
    {
      NativeHop engine(api,bytes,30);float error=0,change=0;
      for(size_t offset=0;offset<input.size();offset+=480){std::array<float,480> output{};float snr=0;
        assert(engine.Process(std::span<const float>(input).subspan(offset,480),output,snr));
        for(size_t i=0;i<480;++i){error=std::max(error,std::abs(output[i]-golden[offset+i]));change=std::max(change,std::abs(output[i]-input[offset+i]));}
      }
      assert(error<=1e-5f&&change>0.001f);std::cout<<"PASS pinned "<<(ll?"Low Latency":"Standard")<<" vs independent upstream vectors, max error="<<error<<std::endl;
    }
    for(int rate:{8000,16000,32000,48000})for(int channels:{1,2}){
      // Keep native STFT/lookahead active; attenuation=0 is upstream immediate-copy bypass.
      NativeHop left(api,bytes,100,true),right(api,bytes,100,true);const auto plan=*DelayPlan::For(rate,channels,left.meta);
      RateAdapter adapter(plan,{&left,&right});const int q=rate/100,blocks=70,impulse=20*q+q/2;
      std::vector<float> wave(blocks*q);float silent_peak=0;
      for(int b=0;b<blocks;++b){OriginalPacket in{};
        in.meta={9,1,static_cast<int64_t>(b)*q,static_cast<uint64_t>(b),1000000000LL+b*10000000LL,0,0,static_cast<uint32_t>(rate),static_cast<uint16_t>(channels),static_cast<uint16_t>(q)};
        // A small calibration pedestal keeps upstream's low-RMS early-return
        // heuristic from suppressing the delayed test impulse; channel 2 stays silent.
        for(int i=0;i<q;++i)in.pcm[i*channels]=33;
        if(impulse>=b*q&&impulse<(b+1)*q)in.pcm[(impulse-b*q)*channels]=8192;
        FilteredPacket out{};assert(adapter.Process(in,out));
        for(int i=0;i<q;++i){wave[b*q+i]=out.pcm[i*channels];assert(std::isfinite(wave[b*q+i]));if(channels==2)silent_peak=std::max(silent_peak,std::abs(out.pcm[i*2+1]));}
      }
      const auto peak=std::max_element(wave.begin(),wave.end(),[](float a,float b){return std::abs(a)<std::abs(b);})-wave.begin();
      const auto difference=static_cast<int64_t>(peak)-impulse-plan.inner_samples;
      std::cerr<<"model="<<ll<<" rate="<<rate<<" channels="<<channels<<" difference="<<difference<<" peak="<<wave[peak]<<" silence="<<silent_peak<<"\n";
      assert(std::abs(difference)<=1&&silent_peak<1e-8f);
      std::cout<<"PASS actual model/rate/"<<channels<<"ch "<<(ll?"LL ":"Standard ")<<rate<<" delay residual="<<difference<<" silent channel peak="<<silent_peak<<std::endl;
    }
  }
}
