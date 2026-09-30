// MOLLY_INCOMING_AUDIO_EXPORTS: appended to audio_transport_impl.cc exactly once.
// Rust_* matches RingRTC's existing native symbol export allowlist.
extern "C" __attribute__((visibility("default"))) int Rust_MollyIncomingAudioVersion() {return 1;}
extern "C" __attribute__((visibility("default"))) int Rust_MollyIncomingAudioConfigure(const float* values,size_t length) {
  if(!values || length!=molly_audio::kParameters)return 0;
  std::array<float,molly_audio::kParameters> parameters{};
  std::copy(values,values+length,parameters.begin());
  molly_audio::settings_bus.Publish(molly_audio::Settings::Unpack(parameters));return 1;
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
