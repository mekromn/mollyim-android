// MOLLY_MOCK_CALL_EXPORTS: local endpoint handles, never Java-supplied pointers.
#define MOLLY_MOCK_EXPORT extern "C" __attribute__((visibility("default")))
MOLLY_MOCK_EXPORT int Rust_MollyMockVersion(){return 1;}
MOLLY_MOCK_EXPORT uint64_t Rust_MollyMockPrepare(){return molly_mock::PrepareSession();}
MOLLY_MOCK_EXPORT int Rust_MollyMockFinish(uint64_t id){return molly_mock::FinishSession(id);}
MOLLY_MOCK_EXPORT void Rust_MollyMockRevoke(uint64_t id){molly_mock::RevokeSession(id);}
MOLLY_MOCK_EXPORT int Rust_MollyMockPreempt(){return molly_mock::PreemptForProduction();}
MOLLY_MOCK_EXPORT int Rust_MollyMockRelease(uint64_t id){return molly_mock::ReleaseSession(id);}
MOLLY_MOCK_EXPORT uint64_t Rust_MollyMockCommand(uint64_t id,int op,int64_t a,int64_t b,int64_t c,int64_t d){return molly_mock::CommandSession(id,op,a,b,c,d);}
MOLLY_MOCK_EXPORT uint64_t Rust_MollyMockConfigure(uint64_t id,const float* data,size_t count){
 if(!data||count!=41)return 0;return molly_mock::ConfigureSession(id,std::span(data,count));
}
MOLLY_MOCK_EXPORT uint64_t Rust_MollyMockLoad(uint64_t id,int direction,uint32_t rate,uint32_t channels,const int16_t* data,size_t count){
 if(!data||!count||count>11520000||(rate!=8000&&rate!=16000&&rate!=32000&&rate!=48000)||channels<1||channels>2||count>static_cast<size_t>(rate)*channels*120)return 0;
 const auto source=std::span(data,count);std::vector<int16_t>pcm(source.begin(),source.end());
 return molly_mock::LoadSession(id,direction,rate,channels,std::move(pcm));
}
MOLLY_MOCK_EXPORT int Rust_MollyMockStatus(uint64_t id,float* data,size_t count){if(!data||count!=128)return 0;return molly_mock::ReadSession(id,std::span(data,count));}
MOLLY_MOCK_EXPORT int Rust_MollyMockDrain(uint64_t id,int tap,int64_t* meta,size_t meta_count,float* pcm,size_t pcm_count){
 if(!meta||!pcm||meta_count!=16||pcm_count!=3840)return -1;return molly_mock::DrainSession(id,tap,std::span(meta,meta_count),std::span(pcm,pcm_count));
}
#undef MOLLY_MOCK_EXPORT
