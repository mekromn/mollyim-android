// MOLLY_CALL_DENOISE_EXPORTS -- checked scalar/control-plane ABI for RingRTC.
#define MOLLY_DF_EXPORT extern "C" __attribute__((visibility("default")))
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseVersion(){return 1;}
MOLLY_DF_EXPORT uint64_t Rust_MollyCallDenoiseBeginFactory(){return molly_denoise::GlobalService().BeginFactory();}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseFinishFactory(uint64_t token){return molly_denoise::GlobalService().FinishFactory(token);}
MOLLY_DF_EXPORT uint64_t Rust_MollyCallDenoiseNewOwner(){return molly_denoise::GlobalService().NewOwner();}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseBegin(uint64_t owner,uint64_t factory){return molly_denoise::GlobalService().BeginSession(owner,factory);}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseEnd(uint64_t owner){return molly_denoise::GlobalService().EndSession(owner);}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseGate(uint64_t owner,int allowed,int reason){
  if((allowed!=0&&allowed!=1)||reason<0||reason>6)return 0;
  return molly_denoise::GlobalService().SetGate(owner,allowed!=0,static_cast<molly_denoise::GateReason>(reason));
}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseInvalidate(uint64_t owner,int reason){
  if(reason<0||reason>6)return 0;
  return molly_denoise::GlobalService().Invalidate(owner,static_cast<molly_denoise::GateReason>(reason));
}
MOLLY_DF_EXPORT int Rust_MollyCallDenoisePaths(const char* library,size_t library_length,const char* standard,size_t standard_length,const char* low_latency,size_t low_latency_length,const char* mobile_fused,size_t mobile_fused_length){
  // Rust supplies owned CString buffers and their checked byte lengths. Copy
  // them synchronously; no raw pointer is retained by the worker factory.
  if(!library||!standard||!low_latency||!mobile_fused||!library_length||!standard_length||!low_latency_length||!mobile_fused_length||library_length>=4096||standard_length>=4096||low_latency_length>=4096||mobile_fused_length>=4096)return 0;
  return molly_denoise::GlobalFactory().SetPaths(std::string(library,library_length),{std::string(standard,standard_length),std::string(low_latency,low_latency_length),std::string(mobile_fused,mobile_fused_length)});
}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseConfigure(int direction,int enabled,int model,float attenuation,int post_filter,float beta,float minimum,float erb,float df){
  if(direction<0||direction>1||model<0||model>2||(enabled!=0&&enabled!=1)||(post_filter!=0&&post_filter!=1))return 0;
  molly_denoise::DenoiseConfig c;c.enabled=enabled!=0;c.model=static_cast<molly_denoise::Model>(model);
  c.parameters={1,attenuation,static_cast<uint32_t>(post_filter),beta,minimum,erb,df,0};
  return molly_denoise::GlobalService().Controls().Update(static_cast<molly_denoise::Direction>(direction),c);
}
MOLLY_DF_EXPORT void Rust_MollyCallDenoiseBypass(int direction,int bypass){
  if(direction>=0&&direction<=1)molly_denoise::GlobalService().Controls().Bypass(static_cast<molly_denoise::Direction>(direction),bypass!=0);
}
MOLLY_DF_EXPORT void Rust_MollyCallDenoiseRetry(int direction){
  if(direction>=0&&direction<=1)molly_denoise::GlobalService().Controls().Retry(static_cast<molly_denoise::Direction>(direction));
}
MOLLY_DF_EXPORT int Rust_MollyCallDenoiseStatus(int direction,float* values,size_t length){
  if(direction<0||direction>1||!values||length!=16)return 0;
  const auto s=molly_denoise::GlobalService().Status(static_cast<molly_denoise::Direction>(direction));
  molly_denoise::ControlSnapshot requested;
  molly_denoise::GlobalService().Controls().Read(static_cast<molly_denoise::Direction>(direction),requested);
  const std::array<float,16> fields{static_cast<float>(s.state),static_cast<float>(s.rate),static_cast<float>(s.channels),s.rate?1000.f*s.delay_samples/s.rate:0,static_cast<float>(s.misses),s.input_peak,s.output_peak,s.snr,s.mean_us,s.p95_us,s.levels_valid?1.f:0.f,s.inference_valid?1.f:0.f,static_cast<float>(s.processed),static_cast<float>(s.updated_us/1000),requested.bypassed?1.f:0.f,0};
  std::copy(fields.begin(),fields.end(),values);return 1;
}
#undef MOLLY_DF_EXPORT
