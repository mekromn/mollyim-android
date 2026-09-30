// MOLLY_INCOMING_AUDIO_EXPORTS: appended to audio_transport_impl.cc exactly once.
// Rust_* matches RingRTC's existing native symbol export allowlist.
extern "C" __attribute__((visibility("default"))) int Rust_MollyIncomingAudioVersion() {return 1;}
extern "C" __attribute__((visibility("default"))) int Rust_MollyIncomingAudioConfigure(
    float p0, float p1, float p2, float p3,
    float p4, float p5, float p6, float p7,
    float p8, float p9, float p10, float p11,
    float p12, float p13, float p14, float p15,
    float p16, float p17, float p18, float p19,
    float p20, float p21, float p22, float p23) {
  // Scalar FFI avoids an unchecked pointer/length contract across Rust/C++.
  const std::array<float,molly_audio::kParameters> parameters{p0,p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,p13,p14,p15,p16,p17,p18,p19,p20,p21,p22,p23};
  molly_audio::GetSettingsBus().Publish(molly_audio::Settings::Unpack(parameters));return 1;
}
extern "C" __attribute__((visibility("default"))) void Rust_MollyIncomingAudioReset() {
  molly_audio::reset_epoch.fetch_add(1,std::memory_order_relaxed);
}
extern "C" __attribute__((visibility("default"))) float Rust_MollyIncomingAudioMeter(int index) {
  return index>=0 && index<6?molly_audio::meter_values[index].load(std::memory_order_relaxed):0;
}
extern "C" __attribute__((visibility("default"))) uint32_t Rust_MollyIncomingAudioFrames() {
  return molly_audio::frame_counter.load(std::memory_order_relaxed);
}
