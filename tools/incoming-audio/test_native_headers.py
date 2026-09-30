#!/usr/bin/env python3
"""Compile our integration headers with Chromium warnings against an API fixture.
This checks C++ contracts/warnings, not a native Android or full WebRTC build.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUB = '''#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
namespace webrtc {
struct AudioFrame {
  enum : size_t { kMaxDataSizeSamples = 7680 };
  std::array<int16_t, kMaxDataSizeSamples> buffer{};
  size_t frames = 480, channels = 1;
  bool is_muted = false;
  size_t samples_per_channel() const { return frames; }
  size_t num_channels() const { return channels; }
  int sample_rate_hz() const { return 48000; }
  bool muted() const { return is_muted; }
  const int16_t* data() const { return buffer.data(); }
  int16_t* mutable_data() { is_muted = false; return buffer.data(); }
  std::span<const int16_t> data_view() const { return std::span(buffer).first(frames * channels); }
  std::span<int16_t> mutable_data(size_t f, size_t c) {
    frames = f; channels = c;
    if (is_muted) buffer.fill(0);
    is_muted = false;
    return std::span(buffer).first(f * c);
  }
};
}
'''
TEST = '''#include "audio/molly_incoming/adapter.h"
#include "audio/molly_incoming/exports.inc"
#include <cassert>
int main() {
  webrtc::AudioFrame frame;
  molly_audio::ReceiveProcessor processor;
  frame.buffer.fill(-12345);
  auto before = frame.buffer;
  processor.Process(&frame);
  assert(frame.buffer == before);
  assert(Rust_MollyIncomingAudioVersion() == 1);
  assert(Rust_MollyIncomingAudioFrames() == 1);
  assert(Rust_MollyIncomingAudioConfigure(1,1,0,1,18,-24,3,10,120,6,0,-6,80,
      0,0,0,0,0,0,0,0,0,0,1) == 1);
  frame.buffer.fill(32767);
  processor.Process(&frame);
  for (auto sample : frame.data_view()) assert(sample <= 16422);
  assert(Rust_MollyIncomingAudioMeter(3) >= 0);
  frame.is_muted = true;
  processor.Process(&frame);
  assert(frame.muted());
}
'''
with tempfile.TemporaryDirectory() as tmp:
    work = Path(tmp)
    headers = work / 'audio/molly_incoming'
    headers.mkdir(parents=True)
    for name in ('dsp.h', 'adapter.h', 'exports.inc'):
        shutil.copy2(ROOT / 'native/incoming-audio' / name, headers / name)
    (work / 'api/audio').mkdir(parents=True)
    (work / 'api/audio/audio_frame.h').write_text(STUB)
    (work / 'test.cc').write_text(TEST)
    subprocess.run([os.environ.get('CXX', 'clang++'), '-std=c++20', '-O2', '-Wall', '-Wextra',
                    '-Werror', '-Wunsafe-buffer-usage', '-Wexit-time-destructors',
                    '-Wglobal-constructors', '-pthread', '-I', str(work), str(work / 'test.cc'),
                    '-o', str(work / 'test')], check=True)
    subprocess.run([str(work / 'test')], check=True)
    print('Strict integration-header fixture passed (not an Android integration build).')
