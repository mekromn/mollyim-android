# DeepFilterNet signed test APK — 171906

Delivery checkpoint: 2026-10-02. This supersedes the older incomplete-build notes in `deepfilternet-progress.md`. Implementation and signed test packaging are complete enough for physical-phone acceptance; production readiness is not claimed. Resume with device feedback, not a new design or reimplementation.

## Delivered APK

- Name: `Molly-Audio-DeepFilterNet-arm64-release.apk` (delivered directly to the owner in chat, not uploaded to this repository).
- Size: 123,878,190 bytes.
- SHA-256: `f82475479c0c8095e6d8314cd1b1cf1a279983cde0d19405dbeaff83c2b12174`.
- Package: `com.mekromn.mollyaudio`; version code **171906**, up from AMOLED **171905**; version name `8.19.2-4`.
- ARM64 only, minimum SDK 27, target SDK 35, not debuggable.
- APK v2 and v3 signatures verify using the ORIGINAL private key.
- Matching public certificate SHA-256: `eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`.
- Same-package, same-certificate, increased-version identity was checked against the signed AMOLED baseline. No new signing key, registration changes, uninstall or data clearing. Private credentials remain outside GitHub.

Install as an update over existing Molly Audio. Both new denoisers start Off. During a call, open **Call audio** and choose **Received — What you hear** or **Sent — Your microphone**. Each direction has independent Standard/Low Latency model choice, suppression limit, post-filter, advanced thresholds, presets, comparison bypass, Retry and reset. Existing incoming EQ/compressor/gain/limiter settings and AMOLED are preserved in the source.

## Build and payload verification

Compiled app source: `377f20c5cb5394762e6efa41e5ba8a8b3b75f76c`. Finalization/verifier source: `5ced061cbda2cfc283425267f24b1ccd906f1400`.

Optimized release assembly and release lint passed in [37044322354](https://github.com/mekromn/mollyim-android/actions/runs/37044322354). Its subsequent original packaging verifier failed. The corrected complete package proof [37049284227](https://github.com/mekromn/mollyim-android/actions/runs/37049284227) passed all steps and produced artifact `11246031704`.

That artifact ZIP's SHA-256 was verified as `17f1234b38dcc92a0c293f759863f41250b7bec7e72e770aff4747263b4adcc2`. Its unsigned APK SHA-256 is `469aec93082d52a6080671d398f2ae61f510ac1e80eaa51c6edbc034069c4e57`.

Fresh checks on the signed APK verified every ZIP member unchanged by signing, all original compiled non-model entries unchanged by finalization, exact model archives/member hashes/metadata, native exports and executable-image hashes, retained native DEX method signatures, attribution resources, compiled manifest/version, and certificate equality. The intermediate expanded `.tar` model aliases were restored to their exact pinned `.tar.gz` archives; decompressed model bytes are identical. No compiled code was changed by finalization or signing.

545 uncompressed ZIP entries pass alignment checks. Native libraries are compressed with `extractNativeLibs=true`; in-ZIP native mmap alignment is not applicable. The three new/modified runtime/RingRTC ELF images pass 16 KB program-segment geometry checks.

## Actual native execution, not just compilation

The complete-package proof extracted and executed the APK's Android ARM64 denoising runtime with AOSP Bionic under QEMU user-mode. Both real models processed 48,000 samples with reset and channel-isolation checks. Maximum absolute error against independent upstream vectors: Standard `2.037268132e-09`, Low Latency `1.490116119e-08`.

This establishes ARM64 native inference/reference behavior. It is not full Android application execution, UI instrumentation, Pixel speed/thermal testing or a live call. The signed APK contains byte-identical payloads to this executed unsigned artifact.

## Acceptance still required

NOT RUN on a physical phone: installation and signed-in-session preservation, rendered controls, live sent/received calls, speakerphone double-talk/echo, Bluetooth/USB switching, video synchronization, and sustained simultaneous-filter performance. Do not report these as passed or claim zero added latency. Compare original retains alignment delay; fully disabling the filter returns to the ordinary path.

A source-change inventory and delivered-binary self-review was performed. No independent reviewer approval is claimed. No merge to main or the release branches was performed. This documentation-only checkpoint skips redundant CI; the binary and source evidence above are from completed machine checks, not inferred from this note.
