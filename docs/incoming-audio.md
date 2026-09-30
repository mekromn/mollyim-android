# Incoming call audio — development branch

The **Incoming audio** pill appears above the existing active-call controls. Its sheet contains master bypass, independent EQ/compressor/gain/limiter switches, 10 EQ bands, compressor timing and knee, presets and numerical meters. Preferences are local. The microphone and encryption code are not modified.

This feature requires a **rebuilt native RingRTC**, not only Kotlin changes. Stock builds deliberately show an unavailable-engine message. The feature workflow compiles the pinned native dependency, verifies both native libraries' exported ABI, substitutes the AAR, and creates an ARM64 release APK. No new signing key is generated. An unsigned APK is not installable until signed with the intended persistent key; it cannot replace an official Molly installation signed by somebody else.

## Local build

Use Linux with the SDK versions from the root Dockerfile, Java 21, Rust/rustup, protoc, clang and the usual WebRTC build prerequisites. The native script checks the pinned RingRTC tag object and refuses unexpected versions or missing patch anchors.

```sh
bash tools/incoming-audio/test_host.sh
bash tools/incoming-audio/build_native.sh
export INCOMING_AUDIO_AAR="$PWD/out/incoming-audio/ringrtc-incoming-audio-arm64.aar"
./gradlew --no-daemon --max-workers=2 -I tools/incoming-audio/native_override.gradle :app:assembleProdStoreRelease
```

Native integration runs after receive mixing and before WebRTC's reverse audio processing, leaving echo-reference processing and both output backends in place. Float32 processing is at the existing mixer rate; high EQ bands are bypassed when unsupported at that rate. The limiter controls **sample peaks before output resampling**; it is not a true-peak limiter or calibrated hearing protection. Master enable is limited immediately; bypass fades back to exact original PCM. Group calls process the combined incoming mix.

The Kotlin controller is initialized by the patched RingRTC initializer so saved settings also load without opening the sheet. JNI reads/writes carry only settings and numerical meters. Per-transport audio state is reset on muted frames and stream gaps; no call audio is recorded or retained for storage.

## Verification boundaries

Host DSP regression tests cover exact bypass, gain, measured EQ, compressor transfer, limiting including startup, linked stereo, channel isolation, rate changes, invalid values and coherent concurrent settings. Sanitizer tests check the host implementation. Patch fixtures check fail-closed anchors and receive-hook ordering; they are **not** a native Android integration build. Standalone Kotlin tests check wire layout and input sanitization.

A complete Android build and device tests remain separate gates. Before production use, test: one-to-one and group calls, background answer, route switching (earpiece/speaker/Bluetooth/USB), speakerphone echo with both people talking, panel open/close/rotation, silence/resume, rapid slider changes, disabled identity, and release JNI shrinking. Do not describe an unbuilt or unsigned artifact as an installable APK.
