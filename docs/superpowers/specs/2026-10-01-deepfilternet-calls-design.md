# Molly Audio: independent DeepFilterNet for sent and received calls

**Date:** 2026-10-01  
**Status:** Written design for owner review. The feature scope was approved in chat; implementation has not started.  
**Application baseline:** `mekromn/mollyim-android`, AMOLED commit `b89c53117079099de3c910e27702046d3a74a279`.  
**Installation identity:** `com.mekromn.mollyaudio`; reuse the existing private signing key. No account migration or re-registration.

## 1. Outcome and boundaries

Add live DeepFilterNet3 speech enhancement to the call screen, with completely independent sent and received enable switches, model selection, configuration, presets and bypass. Keep the AMOLED theme and the existing received EQ, compressor, gain and limiter. The two directions may operate separately or simultaneously.

Received processing changes what this user hears. Sent processing changes microphone audio sent to the other participants. Both operate locally on decoded or captured PCM, without recording it, storing it in logs, or uploading it to an enhancement service. In group calls, received processing acts on the combined incoming mix, not individual participants.

The update leaves registration, account credentials, encryption, message databases, wallpapers, notification sounds and ringtone processing alone. It must install over version code 171905 using the same package and certificate, with a higher version code. Missing signing credentials are a release blocker, not a reason to generate a replacement key or ask the user to uninstall.

## 2. Call-screen experience

Rename the existing **Incoming audio** entry to **Call audio**. Preserve its placement above the existing call controls. The panel has two tabs:

- **Received — What you hear:** a DeepFilterNet section followed by the current EQ/compressor/gain/limiter sections. Rename the existing processing master switch to make its scope explicit: it controls those four received effects, not DeepFilterNet.
- **Sent — Your microphone:** a DeepFilterNet section only. Do not add or reuse the received gain/EQ settings on this path.

Each DeepFilterNet section contains an enable switch; **Standard** / **Low Latency** model selector; attenuation-limit slider; post-filter switch and strength; expandable advanced thresholds; Gentle, Balanced, Strong and Custom presets; reset; and an independent bypass control. The two saved configurations must never overwrite each other. Editing inactive settings is allowed; it must not enable processing or access the microphone.

Show separate requested and effective state: **Off**, **Loading**, **Active**, **Bypassed by you**, **Bypassed: unavailable**, **Bypassed: overloaded**, or **Muted** on the sent tab. A switch being on is not evidence that inference is running. Processing stays active when its tab or the panel is closed. Opening/closing the panel must not reload a model or reset a running stream.

Use the existing theme and control styling, including true-black surfaces and thin outlines in AMOLED mode. Keep close/back behavior, accessibility labels and live numerical slider values. Do not crowd or move the existing mute/hang-up controls.

## 3. Settings and initial values

The following are product defaults, not claims of optimal denoising. Maintain a versioned settings object for each direction. Apply settings as coherent snapshots on the direction's worker; persist after slider release, preset selection, toggles and panel dismissal rather than writing to storage for every audio frame.

| Setting | Initial value and behavior |
|---|---|
| Enabled | Off on both directions after upgrade. |
| Model | Standard selected on both, but not loaded until needed. Low Latency remains independently selectable. |
| Attenuation limit | 30 dB, adjustable 0–100 dB in 1 dB steps. Label 100 as unrestricted model suppression, not guaranteed 100 dB reduction. Treat zero as denoising bypass, not a microphone-mute command. |
| Post-filter | Off initially. Remember a strength of 0.02; adjustable 0–0.05 in 0.001 steps. Effective beta is zero when off. |
| Minimum local SNR threshold | −10 dB initially. |
| ERB-stage upper SNR threshold | 30 dB initially. |
| Deep-filter-stage upper SNR threshold | 20 dB initially. |
| Threshold editing | App-selected range −30 to +60 dB in 1 dB steps; require minimum ≤ deep-filter upper ≤ ERB upper. Validate finite values in both UI and native code. |
| Bypass | Per direction, with aligned original/filtered comparison. It does not bypass the received EQ chain or unmute the microphone. |

Presets only change the selected direction's attenuation and post-filter settings: Gentle = 12 dB/post-filter off; Balanced = 30 dB/post-filter off; Strong = 100 dB/post-filter on at 0.02. Preserve the model choice and enable switch. These are transparent starting configurations; selecting a preset never silently enables a microphone effect. Reset restores this section's defaults, including disabled state, without changing received EQ settings.

Describe advanced values as *model-estimated local signal-to-noise thresholds*. They are not a hardware microphone-sensitivity control or a reliable measured noise floor. Very aggressive settings can attenuate desired speech; provide one-touch reset and bypass.

## 4. Runtime and dependency choice

Use the native Rust `libDF` inference implementation backed by tract, with the official DeepFilterNet3 Standard and Low Latency ONNX model archives. Initial audited source candidate: `Rikorose/DeepFilterNet` commit `d375b2d8309e0935d165700c91da9de862a99c31`.

Bundle both model files once as application assets, not once per direction. Package their licenses and attribution. At build time record their exact source paths, cryptographic hashes and parsed configuration. At first use, copy verified assets to application-private storage if the runtime requires paths; use atomic replacement and reject incomplete files. No runtime model download, executable-code download, or user-exported audio is required.

Compile only inference dependencies needed by the app; exclude training, datasets, demo UI and command-line tooling. Preserve the original model arithmetic and weights. Do not introduce quantization, reduced precision, or substitute a different denoiser under the DeepFilterNet name. CPU inference is the initial backend; GPU/NNAPI acceleration is outside this change.

Build an app-owned, checked native wrapper around Rust's fallible runtime rather than calling the stock C interface unmodified. The audited upstream C interface hard-codes mono processing and uses `expect` for model loading and frame processing. It also exposes fewer controls than `RuntimeParams`. Our wrapper must explicitly configure all chosen settings, validate buffer sizes and rates, return recoverable errors, and contain catchable Rust panics inside Rust before any C boundary. OOM, process aborts and native memory corruption are not recoverable by a generic bypass promise.

Use separate streaming state for every direction and, when stereo is actually negotiated, each channel. Shared immutable model bytes are acceptable; mutable inference, FFT, resampling and history state must not be shared across directions or channels. Do not downmix stereo merely to match the stock mono C API. Tests must detect cross-channel leakage and assess image stability; worst-case performance testing includes four mono inference contexts when both directions have two channels.

## 5. Audio placement

**Received:** existing decoder and mixer → receive DeepFilterNet → existing received EQ/compressor/gain/limiter → existing WebRTC reverse-stream echo reference → existing playback-rate conversion and output.

**Sent:** existing microphone capture and WebRTC echo-cancellation/processing chain → sent DeepFilterNet → existing sending/encoding/encryption path.

The existing receive hook precedes `ProcessReverseAudioFrame`; preserve that ordering. A denoised or fallback frame used for playback must also be the frame supplied to the software echo reference. Do not give echo cancellation a fresh or unprocessed frame while playing a different delayed frame.

The pinned WebRTC capture flow calls `ProcessCaptureFrame`, then an optional asynchronous processor, then `SendProcessedData`. Place the new sent stage after the existing capture processing and before delivery to audio senders. Preserve any already-installed asynchronous processor and downstream mute enforcement. Do not alter local recording sources used outside a call.

Retain existing echo cancellation, automatic gain control and built-in noise-suppression policy for the first build. Evaluate stacked suppression with speech/noise and speakerphone tests. Do not silently disable existing protections or automatically replace their settings when DeepFilterNet is selected. Changes to that policy require a separately documented decision.

## 6. Real-time execution, rates and delay

Use one worker per active direction, with bounded preallocated single-producer/single-consumer audio queues. Model loading, graph optimization, allocation-heavy inference, reset and destruction run off the Android UI and audio callback threads. Callbacks never wait for inference, file I/O, model loading, or a held settings lock. Do not spin up a worker per frame or share a single inference mutex between the directions.

DeepFilterNet's model rate is 48 kHz. Read and validate the selected model's sample rate, hop size, FFT size and lookahead from its actual configuration. Do not assume the two models use identical framing. Adapt WebRTC's callback blocks with streaming input/output resamplers and hop accumulators; bypass unnecessary conversions at 48 kHz. Preserve negotiated channel count and output rate. Never claim resampling restores information absent from a narrowband call.

Reserve a fixed two-WebRTC-block scheduling allowance, initially 20 ms, per enabled direction, in addition to measured model/framing and resampler delay. Allocate bounded history for aligned dry fallback and model lookahead. Reject a model configuration whose required storage exceeds the declared limits; do not expand the queue indefinitely under load. The implementation plan must derive exact buffer sizes from the pinned model metadata before product code is written.

Every packet/result carries a stream generation and an absolute sample position. Account for model lookahead and synthesis delay when identifying the source samples represented by an output: an inference call's input-frame number is not automatically its output's source-frame number. Preserve corresponding capture/playout timestamps and validate video/audio synchronization.

On an inference deadline miss, emit the dry frame for the same expected source interval, not the newest capture block or a previous processed block. Discard late output. Three consecutive missed blocks, or more than 5% missed blocks over a rolling five seconds, latch that direction into **Bypassed: overloaded** for the call until the user retries. These are proposed overload-policy thresholds, not measured device performance. The other direction and received EQ remain independent.

A manual comparison bypass retains alignment delay while suspending inference. Turning the section fully off tears down its extra processing after a bounded fade/reset transition and returns to the original path. It must not replay old samples or release an accumulated backlog to catch up. Model changes prepare the replacement off-thread, validate its framing, reset the stream epoch and crossfade using aligned audio where possible. Where latency changes require a discontinuity, prefer a brief explicit transition to replaying stale speech; do not promise a mathematically seamless zero-delay switch.

When never enabled or stably off, this feature introduces no denoising resampling, inference or additional audio queue. When comparing bypass against active processing, the UI must not mislabel the retained alignment delay as zero. Report model/adapter delay and inference time separately; neither is a measurement of whole-call network latency. Exact device latency, RAM, CPU, thermal behavior and final APK size are verification outputs, not established facts in this design.

## 7. Mute, privacy and failure behavior

The microphone mute rule is stronger than denoiser bypass. Wire invalidation to the authoritative call-state/native audio-enable paths for one-to-one and group calls, not only the on-screen button. The existing app uses `CallManager.setAudioEnable` for one-to-one setup and mute changes; group-call and remote-admin mute paths must be traced in the implementation plan.

When sent audio becomes disallowed: atomically close the new stage's outgoing gate, increment its generation, invalidate queued input/output, and reset inference/resampling/history on its owning worker. Before handing any result or dry fallback to the existing send path, recheck the gate and generation. Muted output follows the existing silence/drop contract. An old worker completion cannot reopen the gate. Unmute starts a fresh generation using only post-unmute microphone samples.

Do not drain pre-mute speech on unmute. Apply the same rule on hang-up, session replacement, permission loss, capture-route replacement and stream-format changes. Clear mutable audio/history buffers on release and reset. No microphone preview plays through the speaker. Audio already transmitted before mute cannot be recalled; the guarantee concerns the buffers and hand-off introduced by this feature.

For recoverable model-load/processing failures or unsupported formats, preserve usable ordinary call audio with a visible reason and independently bypass the failed denoiser. Fallback never overrides mute, authorization, call termination or an unavailable microphone. Never silently switch model, reduce quality, or change channel layout to conceal a failure. A fresh user retry or new call may initialize again; do not enter an unbounded recovery loop.

## 8. Diagnostics and tests required for release

Provide per-direction original/processed peak levels, estimated local SNR, moving-window mean and high-percentile inference time, framing/rate/channel details, configured latency, missed deadlines and effective status. Publish only small numeric snapshots to the UI. Poll at the panel's existing low frequency while visible; no per-frame strings, log uploads, or call identifiers. Display unavailable meters as unavailable rather than zero or stale readings.

Verification must include:

1. Settings isolation, serialization, migration, validation, presets, both-off defaults, and retention of all pre-existing received settings and AMOLED mode.
2. Both actual pinned models loading and processing real speech/noise fixtures; compare aligned native output with the corresponding upstream reference. Fake/stub engines are useful for adapter tests but cannot establish working denoising.
3. Sample-rate and framing adapters at 8, 16, 32 and 48 kHz, plus any other negotiated rate actually supported by the pinned stack; mono and stereo; silence, near-clipping audio, invalid/non-finite data, impulse delay, channel separation and stable memory use.
4. Bit-exact steady-state identity at the new hook with DeepFilterNet off, relative to the existing path at that hook. On received audio this must not bypass EQ effects the user separately enabled.
5. Deterministic slow/failing-worker injection, full queues, late completions, rapid model/enable/bypass edits, startup/teardown races and independent overload handling.
6. Sentinel audio tests proving no pre-mute data from the new stage is delivered after mute or replayed after unmute, including one-to-one, group mute, concurrent failure and route changes.
7. Speakerphone double-talk and echo, receive-reference alignment, Bluetooth/USB/earpiece changes, answering in the background, screen-off calls, video synchronization and panel lifecycle.
8. Optimized ARM64 release build and lint, native/JNI ABI checks after shrinking, dependency/model provenance, packaged notices, version code and certificate equality with the installed baseline.
9. Sustained device calls with both directions and both models, including mixed model choices and thermal load. Distinguish host tests, emulator checks and physical-phone results in the report. Unmeasured Pixel performance is not a passing test.

No APK is described as containing working DeepFilterNet until real native inference is packaged and verified. A UI-only build, stock RingRTC artifact or unsigned APK is not the deliverable. Code changes remain isolated from the current release branch until review; no automatic merge or account-state operation is part of this work.

## 9. Source evidence and remaining verification boundaries

The following source reads informed this design. They establish available APIs and current integration structure, not completed Android integration or physical-device performance.

- [AMOLED baseline](https://github.com/mekromn/mollyim-android/commit/b89c53117079099de3c910e27702046d3a74a279).
- [Current receive adapter](https://github.com/mekromn/mollyim-android/blob/b89c53117079099de3c910e27702046d3a74a279/native/incoming-audio/adapter.h) and [patch ordering](https://github.com/mekromn/mollyim-android/blob/b89c53117079099de3c910e27702046d3a74a279/tools/incoming-audio/patch_ringrtc.py).
- [Pinned WebRTC transport](https://github.com/mollyim/webrtc/blob/7778d/audio/audio_transport_impl.cc).
- [DeepFilterNet source candidate](https://github.com/Rikorose/DeepFilterNet/commit/d375b2d8309e0935d165700c91da9de862a99c31), [Rust runtime controls](https://github.com/Rikorose/DeepFilterNet/blob/d375b2d8309e0935d165700c91da9de862a99c31/libDF/src/tract.rs), and [stock C API](https://github.com/Rikorose/DeepFilterNet/blob/d375b2d8309e0935d165700c91da9de862a99c31/libDF/src/capi.rs).
- [Standard model](https://github.com/Rikorose/DeepFilterNet/blob/d375b2d8309e0935d165700c91da9de862a99c31/models/DeepFilterNet3_onnx.tar.gz) and [Low Latency model](https://github.com/Rikorose/DeepFilterNet/blob/d375b2d8309e0935d165700c91da9de862a99c31/models/DeepFilterNet3_ll_onnx.tar.gz). Archive contents and exact latency have not yet been inspected in this design session; extracting and recording them is a prerequisite to the implementation plan's concrete buffering design.

**Review decision:** Approve or revise this written design before the implementation plan and APK work. Approval does not claim the models have been benchmarked or the feature has been built.
