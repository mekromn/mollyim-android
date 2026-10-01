# Molly Audio DeepFilterNet Calls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add independently configurable, on-device DeepFilterNet3 to sent and received calls, retaining the existing received effects, AMOLED theme, and installed account.

**Architecture:** An app-owned Rust/tract runtime processes one mono model context per negotiated channel, on one worker per active direction. Bounded C++ adapters feed the existing WebRTC receive and send paths; matched delayed original audio is used when a result is unavailable. Kotlin owns separate settings and a two-tab call panel, not the microphone or audio scheduling.

**Tech Stack:** Existing Kotlin/Compose and Java app; pinned RingRTC/WebRTC; C++20; native Rust/libDF with tract; ARM64 Android release build; host, native Android, and physical-device checks.

**Spec:** `docs/superpowers/specs/2026-10-01-deepfilternet-calls-design.md`, committed as `031f6e34f0216dfd1deddc5c4cd31b4b6a2eef71`; approved by the owner in chat on 2026-10-01. Read that document with this plan.

**Status:** Implementation plan for review. The model archives have been inspected; no DeepFilterNet inference, product integration, APK build, or phone benchmark is claimed by this document.

## Global Constraints

- Work on `feature/deepfilternet-calls`, descended from AMOLED source `b89c53117079099de3c910e27702046d3a74a279`. No automatic merge to either release branch or main.
- Installation identity: `com.mekromn.mollyaudio`; reuse the existing private signing key. No account migration or re-registration. Build version code `171906`, greater than installed baseline `171905`; retain minSdk 27, targetSdk 35, and ARM64-only packaging.
- Expected signing-certificate SHA-256: `eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`. Missing credentials block signing; never generate a replacement or request an uninstall.
- Both enable switches start Off; Standard is selected. Attenuation defaults to 30 dB, range 0–100 in 1 dB steps. Zero bypasses denoising, not mute; 100 means unrestricted model suppression.
- Post-filter starts Off, stored beta 0.02; range 0–0.05 in 0.001 steps. Threshold defaults: minimum −10, ERB upper 30, deep-filter upper 20 dB. Range −30 to +60; minimum ≤ deep-filter upper ≤ ERB upper.
- Presets: Gentle = 12 dB/post-filter off; Balanced = 30 dB/post-filter off; Strong = 100 dB/post-filter on at 0.02. They preserve model, enable, bypass, and advanced thresholds. Reset affects only its own denoiser and restores disabled defaults.
- Retain received EQ/compressor/gain/limiter settings and their independent master switch. Preserve existing echo cancellation, automatic gain control, and noise-suppression policy.
- Preserve original model arithmetic and weights: no quantization, reduced precision, substitute denoiser, or silent stereo downmix. No Python runtime, training dependencies, GPU/NNAPI backend, model downloading at runtime, recording, audio uploads, or per-frame log strings.
- Three consecutive missed blocks, or more than 5% missed blocks over a rolling five seconds, latch only the affected direction into overloaded bypass until Retry or a new call.
- Manual comparison bypass retains alignment delay and suspends inference. Stable Off returns to the original path without denoising resampling, inference, or a used audio queue.
- Mute, hang-up, permission loss, route replacement, and session replacement invalidate old sent audio. Fallback cannot override authorization or mute. Fatal OOM/abort/memory corruption are not recoverable-error promises.

## Review Focus

- Mute or hang-up during model loading or a queued inference: a late completion must not reopen transmission; Task 5 sentinel/race tests.
- Switching model while stereo is active or memory is tight: keep channels isolated and ordinary audio usable; Task 2 load failure and Task 4 replacement tests.
- Old group-call callbacks arriving after another call starts: stale ownership must not change the new call's gate; Task 5 owner-token tests.
- Storage unavailable while answering in the background: leave normal calling intact and distinguish unavailable from Active; Task 6 asset/lifecycle tests.
- Repeated underruns, timestamp wrap, and rate changes during video: no backlog, repeated speech, or growing A/V error; Tasks 3–5 and Task 9 tests.

## Evidence and concrete buffering decisions

### Inspected models, not benchmark results

Public-source audit workflow `36920523160`, artifact `11191563259`, verified both archives against their pinned Git blob IDs and recorded all member hashes/configurations. Its ZIP SHA-256 is `739924bf02d1c6ebbb333e86d206a75a3dc2e30090ed13aa78c1a83e0f52239b`. Research is isolated on `research/deepfilter-model-audit`; do not merge that workflow into the product branch.

Source: `Rikorose/DeepFilterNet@d375b2d8309e0935d165700c91da9de862a99c31`.

| Property | Standard | Low Latency |
|---|---:|---:|
| Archive path under upstream `models/` | `DeepFilterNet3_onnx.tar.gz` | `DeepFilterNet3_ll_onnx.tar.gz` |
| Archive bytes | 7,983,136 | 36,359,660 |
| Model sample rate | 48,000 Hz | 48,000 Hz |
| FFT / hop samples | 960 / 480 | 960 / 480 |
| Convolution / deep-filter lookahead hops | 2 / 2 | 0 / 0 |
| Runtime lookahead, `max(conv, df)` | 2 | 0 |
| Deep-filter order / ERB bands / DF bins | 5 / 32 / 96 | 5 / 32 / 96 |
| Source-derived intrinsic delay | 1,440 samples / 30 ms | 480 samples / 10 ms |

Archive SHA-256 values:

```
Standard:    c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616
Low Latency: 5998e58e8ba0e09bb76986ef97b84afa065a571ef282d4a1222f341e3251cf3a
```

The delay formula is the pinned upstream `libDF/src/bin/enhance_wav.rs:134-135`: `fft_size - hop_size + lookahead * hop_size`; `tract.rs:309-314` uses the maximum, not the sum, of the two lookaheads. These are source-derived starting values. Real impulse/reference tests must confirm them before enabling the adapter. The larger Low Latency archive is not evidence of lower CPU use.

### Adapter limits and timeline

Support the native processing rates 8, 16, 32 and 48 kHz, mono or stereo, in exact 10 ms blocks. The pinned capture initializer selects a native APM rate. Keep device-route conversion outside these hooks unchanged. Any newly observed rate/block shape outside this contract explicitly bypasses the denoiser and is reported, not downmixed or misinterpreted.

Use float `webrtc::PushSincResampler` on the worker for native-rate ↔ 48 kHz conversion, one pair per channel; do no conversion at 48 kHz. The pinned 32-tap kernel reports group delay `16/source_rate` seconds for each converter. With native block size `Q=rate/100`:

```
model_delay_seconds = (fft_size-hop_size + lookahead*hop_size)/48000
resampler_delay_seconds = 0 at 48000, otherwise 16/rate + 16/48000
L = 2*Q + Q*ceil((model_delay_seconds + resampler_delay_seconds)/(Q/rate))
```

`L` is the planned total adapter delay in native samples: Standard = 5Q at 48 kHz, 6Q at lower rates; Low Latency = 3Q at 48 kHz, 4Q at lower rates. The two extra blocks reserve the spec's 20 ms scheduling allowance. Additional padding rounds the model/converter delay to a whole callback block, rather than letting queue occupancy change latency. Use rational sample positions internally; record any final fractional-sample alignment residual. Require impulse peaks within one native sample and check phase/amplitude of dry/wet mixtures. A failing alignment test is a blocker, not permission to suppress the assertion.

Per direction, allocate outside callbacks:

- Input SPSC ring: 8 usable packets; output SPSC ring: 16 usable packets. A slot is not reusable until its consumer publishes completion. Use generation tags, not concurrent destructive `reset()` calls on ring indices.
- Dry history: 16 native blocks (160 ms), greater than the maximum 6-block delay plus queue/transition margin. Each packet has room for 480 samples × 2 channels. Retention is bounded and audio is invalidated on epoch changes.
- Fixed 64-byte packet metadata: session token, generation, signed source-sample start, sequence, absolute capture time, elapsed time, NTP time, rate, channels, and frame count. Tokens are local monotonic ownership values, never logged account/call identifiers.
- Original/input packets retain int16 PCM: 1,920 payload bytes plus 64 metadata bytes. Filtered output packets retain float32: 3,840 payload bytes plus 64 metadata bytes. The three rings/history consume 110,080 bytes per direction before indices, scratch buffers, resamplers, and model state; this is not a runtime RAM estimate.
- Per-channel float scratch: two 480-sample native blocks, two 480-sample model hops, and two 960-sample padding/accumulation buffers. Validate sizes before every multiplication/copy; larger metadata is unsupported, never a reason for unbounded growth.

Every emitted block represents source interval `[callback_start-L, callback_start-L+Q)`. Wet results must be tagged after accounting for model/converter delay, not with their input submission time. If wet is missing, select original audio for that same interval. Initial intervals before the current epoch contain silence, never another call's samples. Callback work is bounded: at most 16 output slots examined, no waits for inference or file I/O. Reject work too late to meet its presentation time; never drain stale audio to catch up.

Manual bypass keeps this timeline. Switching fully off uses a bounded two-block fade-out/reset/fade-in transition to the current direct path, then zero extra delay; do not replay the skipped interval. Model replacement first moves to aligned dry, releases old mutable contexts on their worker, then loads the new model. This caps live contexts at four (two channels × two directions) instead of temporarily doubling heavy inference state. New latency begins a new epoch with an explicit transition.

## File map and shared contracts

Existing app prefixes below are abbreviations only:

- **A** = `app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/`
- **U** = `app/src/main/java/org/thoughtcrime/securesms/components/webrtc/v2/`
- **J** = `app/src/test/java/org/thoughtcrime/securesms/webrtc/audio/`
- **N** = `native/call-denoise/`; **R** = `native/deepfilter-runtime/`; **T** = `tools/deepfilter/`

New runtime files: `R/Cargo.toml`, `R/Cargo.lock`, `R/src/lib.rs` (C boundary), `R/src/engine.rs` (fallible mono runtime), `R/src/settings.rs`, `R/include/molly_deepfilter.h`, and `R/tests/runtime.rs`. New adapter files: `N/packet.h`, `N/spsc.h`, `N/timeline.{h,cc}`, `N/worker.{h,cc}`, `N/transport.{h,cc}`, `N/control.{h,cc}`, `N/library.{h,cc}`, and `N/tests/*_test.cc`. Do not put model loading, scheduling, and UI in the existing incoming-audio header.

App files: `A/CallDenoiseSettings.kt`, `A/CallDenoiseController.kt`, `A/CallDenoiseBridge.java`, `A/DeepFilterAssets.kt`; `U/CallAudioControls.kt`, `U/DeepFilterSection.kt`; modify `U/IncomingAudioControls.kt`, `U/CallControls.kt`, `U/CallScreenState.kt`, and `U/ComposeCallScreenMediator.kt`. Preserve `IncomingAudioSettings` and its preference file without migration.

Build/provenance files: `T/models.lock.json`, `T/prepare_models.py`, `T/build_runtime.sh`, `T/patch_ringrtc.py`, `T/test_host.sh`, `T/test_patch.py`, `T/verify_package.py`, `T/test_provenance.py`, `T/fixtures.lock.json`; `.github/workflows/deepfilternet-calls.yml`; modify existing `tools/incoming-audio/build_native.sh` and `app/build.gradle.kts`. Bundle generated, verified assets once in `app/src/main/assets/deepfilter/` and notices through `app/src/main/res/raw/deepfilter_notices.txt` plus the existing license UI.

Contracts, defined in the owning tasks:

- `Direction`, `Model`, and `Preset` are enums in `CallDenoiseSettings.kt`. Direction wire IDs: `RECEIVED=0`, `SENT=1`; model IDs: `STANDARD=0`, `LOW_LATENCY=1`; preset IDs: Gentle, Balanced, Strong, Custom. Settings schema version 1. Unknown enum values are rejected natively; invalid stored values restore defaults for that direction only.
- `DfConfig` has `abi_version:u32`, `attenuation_db:f32`, `post_filter_enabled:u32`, `beta:f32`, `min_snr:f32`, `erb_snr:f32`, `df_snr:f32`, `reserved:u32`. Size 32 bytes. `DfMeta` contains seven u32 values: ABI version, sample rate, hop, FFT, lookahead, DF order, intrinsic delay.
- Rust C ABI, all `noexcept` from C's perspective: `molly_df_create(bytes,len,config,out_handle,out_meta) -> int32`, `molly_df_configure(handle,config) -> int32`, `molly_df_process(handle,input,input_len,output,output_len,out_snr) -> int32`, `molly_df_reset(handle) -> int32`, `molly_df_destroy(handle) -> void`, `molly_df_abi_version() -> uint32`. Header uses explicit pointers and `size_t`; frames are mono floats at model rate. Error codes: 0 success; 1 invalid argument; 2 model/load failure; 3 caught panic/poisoned engine; 4 unsupported metadata; 5 non-finite audio.
- Kotlin `DenoiseSettings(enabled=false, model=STANDARD, attenuationDb=30f, postFilter=false, beta=0.02f, minSnr=-10f, erbSnr=30f, dfSnr=20f)` exposes `sanitized()`, `preset(Preset)`. Manual bypass is separate, per-call state; reset clears it too.
- `CallDenoiseController.initialize(Context)`, `update(Direction,DenoiseSettings,persist:Boolean)`, `save()`, `setBypassed(Direction,Boolean)`, `retry(Direction)`; immutable per-direction `StateFlow` snapshots. JNI applies scalar fields, not pointers to mutable UI objects. Controller must not acquire microphone resources.
- C++ `DirectionProcessor::Process(const OriginalPacket&, std::span<float> output) -> ProcessResult`; result identifies Direct, DelayedDry, Wet, or Muted plus source metadata. `DenoiseControl::SetGate(owner:uint64, allowed:bool, reason:GateReason)`, `BeginSession(owner)`, and `EndSession(owner)` never wait for a worker. `GateReason` = Mute, RemoteMute, End, Permission, Route, Format. Distinct configuration revision and audio generation prevent slider movement from resetting the stream.

## Task 1: Independent settings and model provenance

**Files:** Create `A/CallDenoiseSettings.kt`, `J/CallDenoiseSettingsTest.kt`, `T/models.lock.json`, `T/fixtures.lock.json`, `T/prepare_models.py`, `T/test_provenance.py`.

**Interfaces:** Produces the settings/schema/enum contract above and `prepare_models.py --output <asset-dir>`, which validates downloads and creates the two named assets plus their manifest. No runtime downloads.

- [ ] Write settings tests `defaults_off_and_isolated`, `presets_preserve_enable_model_thresholds`, `reset_is_direction_local`, `reject_nonfinite_and_order_thresholds`, and `serialization_round_trip`. Assert all Global Constraints defaults/ranges; updating SENT leaves RECEIVED and old incoming preferences byte-identical. Preserve in-range values; clamp min first, DF upper to `[min,60]`, ERB upper to `[DF,60]`; round to declared slider steps.
- [ ] Write provenance tests `reject_modified_archive`, `reject_truncated_or_unsafe_members`, `require_pinned_metadata`, `atomic_asset_publish`. Assert the exact SHA-256 and metadata table above, one model copy per asset directory, no escaping/symlink members, and no valid-looking partial destination after interruption. Pin CI-only upstream fixture `assets/noisy_snr0.wav` (Git blob `48c788a76f852de0e5cd30b161b7b240d24926e5`) and its `assets/README.md` attribution; never package test audio in the APK.
- [ ] Run the new tests before implementation and record intended failures, then implement the settings and provenance files. Download fixtures by the pinned commit and record SHA-256 after verifying their Git blob IDs.
- [ ] Run `./gradlew :app:testProdStoreDebugUnitTest --tests '*CallDenoiseSettingsTest'` and `python3 -m unittest discover -s tools/deepfilter -p 'test_provenance.py' -v`. Expected: all new assertions pass; models are validated artifacts, not evidence of inference.
- [ ] Commit only Task 1 files: `git commit -m 'feat(audio): define independent denoiser settings and pinned assets'` after explicit `git add` of those paths.

## Task 2: Fallible native runtime using both real models

**Files:** Create the `R` files in the map, `T/build_runtime.sh`, and `R/tests/reference.rs`.

**Interfaces:** Consumes Task 1 manifests/settings; produces the specified C ABI and `libmolly_deepfilter.so`. A handle has exactly one mutable mono `DfTract`; configure/process/reset/destroy are worker-only.

- [ ] Write ABI/runtime tests: bad sizes/null pointers/ABI versions return error 1; malformed archives return 2 or 4 without process exit; injected catchable panic returns 3 and poisons that handle; NaN/Inf input/output never enters the call path. Test float results, two independent channels, reload after failure, and explicit beta zero when post-filter is disabled.
- [ ] Run tests RED, then implement using `DfParams::from_bytes`, `DfTract::new`, fallible `process`, `set_atten_lim`, `set_pf_beta`, and validated threshold fields. Use `catch_unwind` within Rust, unwind-enabled release builds, and no `expect` across the C boundary. Reset recreates mutable streaming state off-thread; clearing app-owned audio storage is explicit. OOM/abort remain fatal limitations, not claimed recoverable cases.
- [ ] Run each real model on the pinned noisy-speech fixture and compare with an independent driver using the unmodified upstream runtime and identical parameters. After declared delay compensation, require finite output, changed noisy output, and max absolute error ≤1e-5 on the same host backend; ARM comparison tolerance ≤1e-4. Do not compare two calls to our own wrapper as the only reference.
- [ ] Run `cargo test --locked --manifest-path native/deepfilter-runtime/Cargo.toml`; build ARM64 with `bash tools/deepfilter/build_runtime.sh`. Use pinned libDF with default features off and inference-only `tract`; commit the resolved Cargo lock. Verify expected exported symbols and Android minimum API/16 KB ELF alignment. Expected: both actual models work; stub-only passing tests are insufficient.
- [ ] Commit `feat(audio): add checked native DeepFilterNet runtime` with the runtime, lock, tests, and builder, excluding downloaded caches/binaries.

## Task 3: Fixed-delay rate adapters and original-audio alignment

**Files:** Create `N/packet.h`, `N/spsc.h`, `N/timeline.{h,cc}`, `N/tests/timeline_test.cc`, and `T/test_host.sh`.

**Interfaces:** Consumes `DfMeta`; produces bounded packets/queues and `DelayPlan::For(rate,channels,meta) -> optional<DelayPlan>` with `total_samples`, `model_samples_48k`, and converter delay. No inference policy yet.

- [ ] Write `delay_table`, `impulse_and_mixture_alignment`, `source_interval_not_submission_id`, `ring_full_no_overwrite`, `counter_wrap`, `format_change_new_epoch`, and `stereo_no_crosstalk`. Assert 48 kHz totals 2400/1440 samples and lower-rate totals 6Q/4Q; sizes over the contract fail. Test dry/wet impulses within one native sample, mixtures without unexpected extra notches, and monotonic timestamps after wrapping a simulated packet sequence.
- [ ] Run the harness RED, then implement preallocated rings/history and worker-side float resamplers with the exact sizing/timeline rules above. Reframe output by represented source interval, retaining negative startup positions until discarded. At 48 kHz no converter is constructed. Delay compensation cannot assume input and output frame IDs match.
- [ ] Run `bash tools/deepfilter/test_host.sh timeline` with ASan/UBSan, and separately TSan for concurrent ring ownership. Expected: all geometries, slot-full conditions, wraps, and interval assertions pass with stable allocated capacity.
- [ ] Commit `feat(audio): add bounded sample-timed denoiser adapters` with Task 3 files.

## Task 4: Workers, configuration snapshots, bypass, and overload

**Files:** Create `N/worker.{h,cc}`, `N/library.{h,cc}`, `N/control.{h,cc}`, `N/tests/worker_test.cc`.

**Interfaces:** Consumes Tasks 1–3; produces `DirectionProcessor` and control contracts. Load the separately packaged runtime with worker-side `dlopen`/`dlsym`, validating ABI before use; publish an immutable function table. Do not add a circular RingRTC/Rust link dependency.

- [ ] Write deterministic fake-clock tests `late_output_uses_matched_dry`, `three_misses_latch`, `rolling_26_of_500_latches_but_25_does_not`, `receive_failure_does_not_stop_send_or_eq`, `manual_bypass_suspends_inference_keeps_delay`, `rapid_switch_has_no_old_epoch`, and `model_switch_load_failure`. Warm-up, muted, and manual-bypass intervals are not eligible inference misses. Clear the five-second history on epoch changes.
- [ ] Run tests RED, then implement one worker per active direction, serial processing of its one/two channel contexts, bounded queue consumption, coherent settings snapshots, poison/unavailable fallback, and the specified off/model-change transitions. Model replacement may temporarily use ordinary/delayed dry audio; it must not create eight simultaneous contexts or silently select another model.
- [ ] Add numeric diagnostics: original/filtered peaks, optional estimated SNR, mean/p95 inference time over the last 500 valid samples, delay, rate/channel/framing, miss counts, and effective state. Publish at most every 100 ms; empty/stale measurements are unavailable, not zero. No audio, external call IDs, per-frame strings, or blocking UI reads.
- [ ] Run `bash tools/deepfilter/test_host.sh worker` under ASan/UBSan and separate TSan. Instrument callbacks to assert zero allocation and no worker/settings-lock wait after construction; process 100,000 synthetic callbacks with bounded capacity. Expected: pass all injected slow/error/edit cases and no post-latch recovery loop.
- [ ] Commit `feat(audio): run independent denoisers with deadline-safe bypass` with Task 4 files.

## Task 5: RingRTC hooks, authoritative mute, and lifecycle

**Files:** Create `N/transport.{h,cc}`, `N/tests/transport_test.cc`, `T/patch_ringrtc.py`, `T/test_patch.py`; modify `native/incoming-audio/adapter.h` and `tools/incoming-audio/build_native.sh`. Patches target exact upstream paths `audio/audio_transport_impl.{h,cc}`, `api/audio/audio_frame.{h,cc}`, `audio/BUILD.gn`, and RingRTC `src/android/api/org/signal/ringrtc/{CallManager,GroupCall}.java`; create RingRTC `src/android/api/org/signal/ringrtc/CallDenoiseGate.java` and `src/rust/src/android/api/call_denoise.rs`, registering the JNI module in `src/rust/src/lib.rs`. Also modify app `app/src/main/java/org/thoughtcrime/securesms/service/webrtc/SignalCallManager.java` for authoritative route/session notifications. Keep all generated dependency edits in the patcher, not untracked checkout mutations.

**Interfaces:** Consumes `DirectionProcessor`, gate/session contracts, and the existing incoming `Processor::Process`. Produces receive and sent hook integration and checked native settings/status entry points.

- [ ] Write sentinel tests `mute_during_inference`, `mute_during_load`, `remote_admin_mute`, `unmute_never_replays`, `stale_group_callback`, `hangup_new_call`, `permission_route_format_reset`, and `fallback_cannot_unmute`. Inject distinguishable pre-mute/post-mute samples; assert no pre-mute interval is handed downstream after the gate-close linearization point, including late model results. Audio already handed downstream before that point is outside this feature's buffer guarantee.
- [ ] Run RED, then implement receive processing inside the existing receive adapter so float denoiser output goes directly into float EQ/compression before the existing final PCM conversion. Preserve the mixer → denoiser → EQ → reverse-reference ordering and bit-exact off behavior; don't add a redundant int16 quantization between denoising and EQ.
- [ ] Insert sent processing at `SendProcessedData` before its `capture_lock_` and before fan-out to senders. This preserves `ProcessCaptureFrame` and any existing asynchronous processor. Stamp `{owner,generation,source_start}` at the beginning of capture, before APM or the asynchronous processor, in three new AudioFrame metadata fields carried through `CopyFrom` and moves. At the sent hook reject mismatched stamps; never retag a pre-mute frame as post-unmute merely because an upstream asynchronous processor delivered it late. Test that case with an intentionally delayed pre-existing asynchronous processor. Retain metadata for the represented delayed capture interval. Downstream track mute remains authoritative; processing is scoped to call transport, not app voice-note recording.
- [ ] Wire gate invalidation to the traced methods: one-to-one `CallManager.setAudioEnable`; groups `GroupCall.setOutgoingAudioMuted` and `setOutgoingAudioMutedRemotely`; group leave/disconnect/dispose/ended and one-to-one reset/close/hang-up. The app path for administrator mute is `SignalCallManager.onRemoteMuteRequest` → `GroupConnectedActionProcessor.handleRemoteMuteRequest` → `GroupCall.setOutgoingAudioMutedRemotely`. Pre-join/join/incoming group paths already call `setOutgoingAudioMuted`; cover these calls, not just the call-screen action. Owner tokens prevent old calls enabling a replacement session. Use app `SignalCallManager.onAudioDeviceChanged` (distinct from `onNetworkRouteChanged`) to invalidate capture on actual device replacement, even when the PCM format is unchanged; require a fresh capture epoch before resuming. Permission loss or capture stop likewise cannot reopen via a model Retry. Route/permission/session notifications use the same owner-checked `CallDenoiseGate` API.
- [ ] Gate close is atomic before scheduling reset; every hand-off and delayed dry selection validates owner/generation/permission again. Producer/consumer owners invalidate their own queues; the worker clears histories/state. Unmute creates a new epoch and cannot accept an old worker's completion. Test ordering with deterministic barriers, including mute racing the final hand-off, rather than relying on timing sleeps.
- [ ] Run `python3 tools/deepfilter/test_patch.py`, `bash tools/deepfilter/test_host.sh transport`, and existing `bash tools/incoming-audio/test_host.sh`. Compile the actual pinned patched RingRTC on ARM64; assert both old DSP and new bridge ABI survive. Source-anchor fixtures alone do not validate native integration. Expected: exact off PCM and matching rendered/AEC-reference blocks, both async and normal capture paths, all sentinel tests pass.
- [ ] Commit `feat(audio): integrate call denoising with authoritative mute gates` with Task 5 files.

## Task 6: Persistent controller and verified private assets

**Files:** Create `A/CallDenoiseController.kt`, `A/CallDenoiseBridge.java`, `A/DeepFilterAssets.kt`, `J/CallDenoiseControllerTest.kt`, `J/DeepFilterAssetsTest.kt`; extend the patcher's existing initialization hook.

**Interfaces:** Produces the public controller/StateFlow API above. Native settings/status IDs are versioned; annotated entry points survive R8. Store separate `received.*` and `sent.*` keys in `call_denoise_v1`, not `incoming_audio_v1`.

- [ ] Write tests `upgrade_preserves_existing_preferences`, `background_init_idempotent`, `locked_storage_fails_open_for_call`, `interrupted_asset_copy`, `corrupt_asset_rejected`, `missing_jni_not_active`, and `no_model_load_for_disabled_edit`. Assert no controller method opens an AudioRecord, triggers registration, or changes an incoming EQ key.
- [ ] Run RED, then implement worker-side verified model installation using temporary files and atomic rename into app-private storage. Package-only bundled sources; no arbitrary external path or audio URI. Initialize settings for background calls without opening the panel. Keep request/effective states separate and publish only numeric native snapshots.
- [ ] Run `./gradlew :app:testProdStoreDebugUnitTest --tests '*CallDenoiseControllerTest' --tests '*DeepFilterAssetsTest'`. Expected: versioned round trips and independent error recovery pass; repeated initialization cannot reset an active denoiser.
- [ ] Commit `feat(audio): persist independent call denoising controls safely` with Task 6 files.

## Task 7: Two-tab AMOLED-compatible call panel

**Files:** Create `U/CallAudioControls.kt`, `U/DeepFilterSection.kt`, `app/src/main/res/values/call_denoise.xml`; modify the existing U files listed above. Create `app/src/androidTest/java/org/thoughtcrime/securesms/webrtc/audio/CallAudioControlsTest.kt`.

**Interfaces:** Consumes the controller; exposes `CallAudioControls(onSheetDisplayChanged: (Boolean)->Unit)`. Refactor the existing received-effects contents into a reusable composable without changing its settings/controller. Add a distinct call-audio-sheet visibility flag/callback instead of reusing audio-device-picker state.

- [ ] Write Compose tests for exact labels `Call audio`, `Received — What you hear`, `Sent — Your microphone`; independent enable/model/preset/reset/bypass; editing disabled settings; live slider numbers; two status displays; close/back/rotation; and return to the existing received settings. Received effects use the label `EQ, compressor, gain and limiter`, not an ambiguous all-audio master switch.
- [ ] Run the UI tests RED, then implement all controls/ranges from Global Constraints, expandable thresholds, Retry, and diagnostics including retained bypass delay. Preset selection never enables a filter. Tab changes do not reload/reset inference. Poll meters at 100 ms only while visible. Keep mute/hang-up layout and AMOLED outline/black-surface behavior.
- [ ] Run instrumentation tests on an available emulator; if unavailable, mark them not run and provide build/host evidence separately. Re-run `python3 tools/amoled/test_theme.py`. Expected: no theme/old-control regression, no sheet unexpectedly hiding beneath the device picker, accessible labels and values present.
- [ ] Commit `feat(ui): add sent and received DeepFilterNet call controls` with Task 7 files.

## Task 8: Reproducible ARM64 release and same-key update

**Files:** Create `.github/workflows/deepfilternet-calls.yml`, `T/verify_package.py`, `T/test_package.py`; modify `app/build.gradle.kts` and existing native builder. Add packaged assets/notices and attribution access without adding a settings subsystem.

**Interfaces:** Produces the verified patched AAR, runtime library, release APK, model/dependency manifest, and a verification report. Preserve existing native symbols; the old unchanged AAR is not an acceptable cache hit for this feature.

- [ ] Write package tests rejecting stock RingRTC, missing models/runtime/notice, changed models, wrong JNI descriptors, debug APK, wrong package/certificate, and non-increasing version. Include a test that verifies both models load via the packaged native boundary on ARM64, not only as host artifacts.
- [ ] Run RED, then build using NDK `28.0.13004108`, JDK 21, SDK/build tools already pinned by the baseline. Cache keys include every denoiser/native/patch/lock/metadata input. Build runtime with no training/default embedded model; add it once to AAR `jni/arm64-v8a/`. Do not disable buffer-safety, shrinking, lint, or signature checks to pass.
- [ ] Run host tests, actual native build/ABI tests, then separate `:app:lintVitalProdStoreRelease` and `:app:assembleProdStoreRelease` Gradle invocations. Preserve the proven one-worker/no-parallel, in-process Kotlin, 12 GB heap approach. Pass `-I tools/incoming-audio/native_override.gradle`, verified AAR environment, and existing package/title arguments. Require all mandatory machine gates; retain failures and exact source/artifact hashes.
- [ ] Download and verify the unsigned artifact against GitHub's digest; sign only offline with the original private key. Verify v2/v3, certificate equality, compiled package/version, embedded models, DEX descriptors, ELF alignment, and exported ABI with `python3 tools/deepfilter/verify_package.py --apk <signed.apk> --baseline <amoled.apk>`. Never print private credentials or commit them.
- [ ] Commit `build: verify and package the DeepFilterNet call update` with Task 8 source/configuration only. Deliver a downloadable signed test APK only after real inference is packaged and tested; distinguish untested phone behavior from successful build checks.

## Task 9: Physical-call acceptance and final review

**Files:** Create `docs/deepfilternet-device-validation.md` and `T/summarize_benchmark.py`; test with synthetic fixtures and voluntary call testing, never record live call audio for reports.

**Interfaces:** Consumes the signed test APK and numeric diagnostics; produces a host/emulator/phone-separated acceptance report and final branch review.

- [ ] Before installing, verify backup availability and the package/certificate match without touching registration. Install as an update only. Confirm existing account, messages, AMOLED selection and received-effect settings remain; no new phone-number verification belongs to this change.
- [ ] Exercise Off, received-only, sent-only, both Standard, both Low Latency, and each mixed-model combination; include worst-case two channels per direction in the native harness. Run at least 20 minutes for each both-enabled model pairing, with screen off and video/thermal load. Record mean/p95 inference, misses, bounded backlog, process RAM trend, temperature/thermal state, and reported filter delay. No unmeasured Pixel numbers count as a pass.
- [ ] Test earpiece, speakerphone double-talk, Bluetooth and USB route changes, background answer, mute/unmute from all accessible controls, remote administrator mute, hang-up/reconnect, rapid model switches, and video synchronization. Check both ends' speech for unwanted attenuation with the unchanged built-in suppressors. Numeric model SNR is not a calibrated intelligibility metric.
- [ ] Validate results against every spec section; independently review the whole diff when a separate reviewer is available, otherwise explicitly record a self-review rather than inventing reviewer approval. A failed privacy/safety test blocks release. Overloaded hardware remains visibly bypassed; it must not be described as supporting both filters successfully.
- [ ] Commit the evidence report without audio, personal identifiers or secrets. Keep unperformed checks marked NOT RUN. Do not automatically merge branches. A signed test build can be provided for these checks, but production readiness requires their results.

## Execution handoff

Task dependencies: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9. Settings/provenance and native/runtime tests can be developed separately after the shared contracts are frozen; final integration and signing remain sequential.

Recommended execution in this chat: implement task-by-task with explicit red/green evidence and GitHub Actions for native/Android jobs. Do not promise a separate subagent reviewer when no such execution tool is available. Read and approve this plan before product implementation; the earlier approval covered the written design.

## Primary source references

- Approved specification and AMOLED baseline paths above; baseline APK report records package `com.mekromn.mollyaudio`, version `171905`, and the certificate in Global Constraints.
- Model inspection: https://github.com/mekromn/mollyim-android/actions/runs/36920523160 ; upstream pinned models and runtime: https://github.com/Rikorose/DeepFilterNet/tree/d375b2d8309e0935d165700c91da9de862a99c31 .
- Processing-rate/capture/receive ordering: https://github.com/mollyim/webrtc/blob/7778d/audio/audio_transport_impl.cc . Converter contract/delay: `common_audio/resampler/push_sinc_resampler.h` and `sinc_resampler.h` at the same ref.
- Group mute/remote mute/track disable: https://github.com/mollyim/ringrtc/blob/v2.69.5-1/src/android/api/org/signal/ringrtc/GroupCall.java . App source trace: `service/webrtc/GroupConnectedActionProcessor.java:200-236` and `SignalCallManager.java:374-375,1073-1079` at the AMOLED baseline.
