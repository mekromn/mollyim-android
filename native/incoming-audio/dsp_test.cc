#include "dsp.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <thread>
#include <vector>
using namespace molly_audio;
static float db(float x) { return 20.0f * std::log10(std::max(std::abs(x), 1e-9f)); }
static Settings only_gain(float gain) {
  Settings s; s.enabled = true; s.eq_enabled = false;
  s.compressor_enabled = false; s.limiter_enabled = false; s.gain_db = gain;
  return s;
}
static void steady(Processor& p, const Settings& s, float value, int rate=48000, int ch=1) {
  std::vector<float> block(rate / 100 * ch, value);
  for (int n=0; n<200; ++n) { std::fill(block.begin(),block.end(),value); p.Process(block.data(), block.size()/ch, ch, rate, s); }
}
int main() {
  {
    Processor p; Settings s;
    std::vector<float> a{0.0f,-0.2f,0.37f,1.0f,-1.0f}; auto b=a;
    p.Process(a.data(),a.size(),1,48000,s); assert(a==b);
    std::cout << "PASS exact default bypass\n";
  }
  {
    Processor p; Settings s=only_gain(6.0f); steady(p,s,0.1f);
    float a=0.1f; p.Process(&a,1,1,48000,s); assert(std::abs(db(a/0.1f)-6)<0.02f);
    s.enabled=false; steady(p,s,0.1f); a=-0.356f; p.Process(&a,1,1,48000,s); assert(a==-0.356f);
    std::cout << "PASS gain and return to exact bypass\n";
  }
  {
    Processor p; Settings s=only_gain(0); s.compressor_enabled=true;
    s.threshold_db=-24; s.ratio=4; s.knee_db=0; s.makeup_db=0;
    steady(p,s,std::pow(10.0f,-6.0f/20));
    float a=std::pow(10.0f,-6.0f/20); p.Process(&a,1,1,48000,s);
    assert(std::abs(db(a)-(-19.5f))<0.08f);
    std::cout << "PASS compressor static 4:1 transfer\n";
  }
  {
    Processor p; Settings s=only_gain(18); s.limiter_enabled=true; s.ceiling_db=-3;
    steady(p,s,0);
    std::vector<float> a(960);
    for(size_t i=0;i<a.size()/2;++i) {a[i*2]=(i%2)?1:-1; a[i*2+1]=a[i*2]*0.25f;}
    p.Process(a.data(),480,2,48000,s);
    for(size_t i=0;i<a.size()/2;++i) {
      assert(std::abs(a[i*2])<=std::pow(10.0f,-3.0f/20)+1e-5f);
      assert(std::abs(a[i*2+1]-a[i*2]*0.25f)<1e-6f);
    }
    std::cout << "PASS limiter ceiling and linked stereo\n";
  }
  {
    Processor p; Settings s=only_gain(0); s.eq_enabled=true; s.eq_db[5]=6;
    double in=0,out=0; double phase=0;
    for(int block=0;block<150;++block) {
      std::vector<float> a(480);
      for(auto& v:a) {v=0.05f*std::sin(phase);phase+=2*3.141592653589793/48; if(block>100)in+=v*v;}
      p.Process(a.data(),a.size(),1,48000,s);
      if(block>100)for(auto v:a)out+=v*v;
    }
    assert(std::abs(10*std::log10(out/in)-6)<0.08);
    std::cout << "PASS 1 kHz EQ +6 dB measured response\n";
  }
  {
    Processor p; Settings s=only_gain(0); s.eq_enabled=true; s.eq_db.fill(12);
    for(int rate:{8000,16000,32000,44100,48000,96000}) {
      std::vector<float> a(rate/100*2,0.02f);
      for(size_t i=0;i<a.size();i+=2)a[i]=0;
      p.Process(a.data(),a.size()/2,2,rate,s);
      for(size_t i=0;i<a.size();i+=2) {assert(a[i]==0);assert(std::isfinite(a[i+1]));}
    }
    std::cout << "PASS rate changes, stereo isolation and high bands\n";
  }
  {
    Settings s=only_gain(std::numeric_limits<float>::quiet_NaN());
    s.ratio=0; s.eq_db[0]=std::numeric_limits<float>::infinity();
    s.Sanitize(); assert(s.gain_db==0);assert(s.ratio>=1);assert(s.eq_db[0]==0);
    Processor p; std::vector<float> a(480,0.1f);a[4]=std::numeric_limits<float>::quiet_NaN();
    p.Process(a.data(),a.size(),1,48000,s);
    for(float v:a)assert(std::isfinite(v));
    std::cout << "PASS invalid settings and nonfinite PCM handling\n";
  }
  {
    SettingsBus bus; std::atomic<bool> done{false};
    std::thread t([&] {for(int i=0;i<10000;++i){ Settings s; s.gain_db=float(i%19);s.eq_db.fill(s.gain_db/2);bus.Publish(s);}done=true;});
    Settings s;
    while(!done.load())if(bus.Read(s)){for(float v:s.eq_db)assert(v==s.gain_db/2);}
    t.join(); std::cout << "PASS coherent concurrent settings snapshots\n";
  }
  {
    Processor p; Settings s=only_gain(18); s.limiter_enabled=true;s.ceiling_db=-6;
    std::vector<float> a(960,1.0f);
    p.Process(a.data(),480,2,48000,s);
    for(float value:a)assert(std::abs(value)<=Linear(-6)+1e-6f);
    std::cout << "PASS limiter from first enable sample\n";
  }
  std::cout << "9 tests passed\n";
}
