#!/usr/bin/env python3
"""Strict C++ compile against a small AudioFrame API fixture, not Android execution."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
STUB=r'''#pragma once
#include <array>
#include <span>
#include <optional>
#include <cstdint>
namespace webrtc {
struct AudioFrame {
 enum:size_t{kMaxDataSizeSamples=7680};
 std::array<int16_t,kMaxDataSizeSamples> buffer{};
 uint64_t molly_owner_=0,molly_gate_generation_=0,molly_stream_generation_=0;
 int64_t molly_source_start_=0,elapsed_time_ms_=-1,ntp_time_ms_=-1;
 size_t frames=480,channels=1;int rate=48000;bool mute=false;
 std::optional<int64_t> time;
 size_t samples_per_channel()const{return frames;}size_t num_channels()const{return channels;}
 int sample_rate_hz()const{return rate;}bool muted()const{return mute;}
 auto data_view()const{return std::span(buffer).first(frames*channels);}
 auto mutable_data(size_t f,size_t c){frames=f;channels=c;if(mute)buffer.fill(0);mute=false;return std::span(buffer).first(f*c);}
 void Mute(){mute=true;buffer.fill(0);}
 auto absolute_capture_timestamp_ms()const{return time;}
 void set_absolute_capture_timestamp_ms(int64_t t){time=t;}
 void clear_absolute_capture_timestamp(){time.reset();}
};
}
'''
TEST=r'''#include "audio/molly_denoise/webrtc_adapter.h"
#include "audio/molly_incoming/adapter.h"
#include "audio/molly_denoise/exports.inc"
void ApiContract(webrtc::AudioFrame& frame) {
 molly_denoise::WebRtcDenoiser transport;
 molly_audio::ReceiveProcessor receive;
 transport.StampCapture(&frame);
 auto lease=transport.ProcessSent(&frame);
 receive.Process(&frame,transport.Receiver());
 transport.CaptureStopped();
}
'''
with tempfile.TemporaryDirectory() as tmp:
    work=Path(tmp);target=work/'audio/molly_denoise';target.mkdir(parents=True)
    for path in (ROOT/'native/call-denoise').iterdir():
        if path.suffix in ('.h','.cc','.inc'):shutil.copy2(path,target/path.name)
    shutil.copy2(ROOT/'native/deepfilter-runtime/include/molly_deepfilter.h',target/'molly_deepfilter.h')
    old=work/'audio/molly_incoming';old.mkdir()
    for name in ('adapter.h','dsp.h'):shutil.copy2(ROOT/'native/incoming-audio'/name,old/name)
    (work/'api/audio').mkdir(parents=True);(work/'api/audio/audio_frame.h').write_text(STUB)
    (work/'contract.cc').write_text(TEST)
    flags=[os.environ.get('CXX','clang++'),'-std=c++20','-O1','-pthread','-Wall','-Wextra','-Werror','-Wunsafe-buffer-usage','-Wexit-time-destructors','-Wglobal-constructors','-fno-exceptions','-fno-rtti','-I',str(work),'-I',str(target)]
    for source in [work/'contract.cc']+[target/f'{name}.cc' for name in ('control','library','worker','gate','transport','service')]:
        subprocess.run(flags+['-c',str(source),'-o',str(work/(source.stem+'.o'))],check=True)
print('Strict gate/worker/frame integration compile passed (API fixture, not Android).')
