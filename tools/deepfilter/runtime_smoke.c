/* CI-only native boundary test. No call or microphone audio is recorded. */
#include "molly_deepfilter.h"
#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define REQUIRE(x) do {if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}}while(0)
#define LOAD(n) __typeof__(&molly_df_##n) api_##n=(__typeof__(&molly_df_##n))dlsym(lib,"molly_df_"#n);REQUIRE(api_##n)
static unsigned char *read_all(const char*path,size_t*length){FILE*f=fopen(path,"rb");REQUIRE(f);REQUIRE(!fseek(f,0,SEEK_END));long n=ftell(f);REQUIRE(n>0&&n<50000000);rewind(f);unsigned char*p=malloc((size_t)n);REQUIRE(p);REQUIRE(fread(p,1,(size_t)n,f)==(size_t)n);fclose(f);*length=(size_t)n;return p;}
int main(int argc,char**argv){
 REQUIRE(argc==6);void*lib=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!lib){fprintf(stderr,"%s\n",dlerror());return 1;}
 LOAD(abi_version);LOAD(create);LOAD(configure);LOAD(process);LOAD(reset);LOAD(destroy);REQUIRE(api_abi_version()==1);
 DfConfig c={1,30,0,0.02f,-10,30,20,0};DfMeta meta={0};DfHandle*h=NULL;
 REQUIRE(api_create(NULL,0,&c,&h,&meta)!=0);size_t bytes,n,gold_n;unsigned char*m=read_all(argv[2],&bytes);
 float*input=(float*)read_all(argv[3],&n),*gold=(float*)read_all(argv[4],&gold_n);REQUIRE(n==48000*sizeof(float)&&n==gold_n);
 REQUIRE(api_create(m,bytes,&c,&h,&meta)==0&&h);REQUIRE(meta.sample_rate==48000&&meta.hop==480&&meta.fft==960);
 REQUIRE(meta.intrinsic_delay==(unsigned)atoi(argv[5]));
 float output[480],snr=0;double max_error=0,changed=0;for(size_t off=0;off<48000;off+=480){REQUIRE(api_process(h,input+off,480,output,480,&snr)==0);REQUIRE(isfinite(snr));for(size_t i=0;i<480;i++){REQUIRE(isfinite(output[i]));double e=fabs(output[i]-gold[off+i]);if(e>max_error)max_error=e;changed+=fabs(output[i]-input[off+i]);}}
 REQUIRE(max_error<=1e-4&&changed>0.001);
 REQUIRE(api_reset(h)==0);REQUIRE(api_process(h,input,480,output,480,&snr)==0);for(size_t i=0;i<480;i++)REQUIRE(fabs(output[i]-gold[i])<=1e-4);
 DfConfig invalid=c;invalid.abi_version=99;REQUIRE(api_configure(h,&invalid)!=0);REQUIRE(api_process(h,input,479,output,480,&snr)!=0);
 // Independent silent channel must not inherit the previous context's speech.
 DfHandle*other=NULL;DfMeta other_meta={0};REQUIRE(api_create(m,bytes,&c,&other,&other_meta)==0&&other);float silence[480]={0};REQUIRE(api_process(other,silence,480,output,480,&snr)==0);for(size_t i=0;i<480;i++)REQUIRE(fabs(output[i])<1e-7);
 api_destroy(other);api_destroy(h);free(m);free(input);free(gold);dlclose(lib);
 printf("{\"native_execution\":true,\"intrinsic_delay\":%u,\"max_reference_error\":%.10g,\"samples\":48000,\"reset_and_channel_isolation\":true}\n",meta.intrinsic_delay,max_error);return 0;
}
