# Incoming call audio implementation plan

**Goal:** receive-only audio controls on the existing call screen.
**Architecture:** float DSP in the native receive mixer path; Rust JNI bridge in RingRTC; app-owned Java bridge, Kotlin state/persistence and Compose sheet. Upstream build retains stock AAR with explicit unavailable UI; feature build replaces only RingRTC with verified patched AAR.
**Tech stack:** C++17, Rust JNI, Java, Kotlin/Compose, Gradle, GitHub Actions.
**Spec:** ../specs/2026-09-30-incoming-audio.md

## Global constraints
No outgoing audio changes, reduced sample rates, platform-effect dependence, audio recording, key changes, forced backend, or unverified APK claims. Defaults and bounds exactly as spec. No audio-thread waits or allocations.

## Tasks
1. Native engine: write and run host tests for bypass, gain, EQ, compression, limiting, channel isolation, invalid input, rate changes and concurrent settings; then implement native/incoming-audio/dsp.h. Compile with strict warnings and sanitizers. Assert signal behavior, not source strings.
2. Native adapter: patch the pinned WebRTC receive path before reverse processing, add Rust JNI exports, and test patch anchors and native exports. Keep per-transport DSP state; reset between calls.
3. Call-screen feature: Java native availability bridge, Kotlin settings/controller and private persistence, Compose bottom sheet, call-screen entry point and call-lifecycle hooks. Fail visibly if DSP unavailable; no silent Java-only fallback.
4. Build: scripted pinned RingRTC build, native AAR verification and local artifact substitution; automated native tests and release APK build. Keep signing externally configured, no generated throwaway signing identities.
5. Verify: run all available host checks, inspect diffs; attempt real Android/CI build and report precise failures. Record device route/echo tests as outstanding unless actually run.

## Review focus
Persisted NaN; rapid preset changes; group-call factory reuse; sample-rate changes; bypass identity after transitions; Android route resampling after limiter; absent custom AAR; release JNI shrinking; call sheet auto-hide.
