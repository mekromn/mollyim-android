# Molly Audio Mock Call Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an in-place Molly Audio update that records and auditions real handset/speakerphone call audio, exercises the existing sent/received effects, and displays useful live statistics and correctly labeled latency.

**Architecture:** Create an explicitly local test endpoint inside the same WebRTC audio engine used by a production factory, without creating a PeerConnection or signaling a call. Bind the existing transport, mixer, capture processing, Java/Oboe backend and effect implementations to lab-owned controls; attach bounded clip sources and recording sinks only to that lab endpoint. A production-priority resource coordinator governs capture, output, UI actions and retirement.

**Tech Stack:** Existing Kotlin/Compose and Java app; pinned Molly RingRTC/WebRTC; C++20; existing checked DeepFilterNet Rust runtime; ARM64 Android; host tests, native integration tests, Android instrumentation and signed release verification.

**Spec:** `docs/superpowers/specs/2026-10-02-mock-call-lab-design.md` at `c56827a4de079003346ae60e5c6b0ca5610fd15e` (v3). The owner requested “Build it” after the v3 specification. This document is the implementation plan for review; it does not claim product implementation, test execution or an APK.

## Global Constraints

- Work only on `feature/mock-call-lab`, currently based on `c56827a4de079003346ae60e5c6b0ca5610fd15e`; retain product baseline `3935ddf30aecd90f19a414095a9b78a549d1a146`. Do not merge automatically into the DeepFilterNet branch or main.
- Preserve package `com.mekromn.mollyaudio`, original certificate SHA-256 `eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`, ARM64-only optimized non-debuggable release, minimum SDK 27 and target SDK 35. Reserve version code 171907; verify it exceeds the most recent supplied build before signing. No account, database-schema, registration, uninstall or data-clear operation.
- Reuse `RingRtcDynamicConfiguration.getAudioConfig()` and the actual factory/backend configuration. Normal handset mode is the communication earpiece route, not Android MODE_NORMAL or cellular MODE_IN_CALL. Do not force a different input/backend to obtain convenient test results.
- Use the existing DeepFilterNet models and arithmetic without quantization or model replacement; keep the exact current model/runtime locks. Do not reimplement the denoiser, received EQ, compressor, limiter or gain algorithm.
- Lab controls must be isolated in both Kotlin and C++; no overwrite-and-restore of production singleton settings. Only explicit Apply to calls publishes selected settings. Existing persisted preferences remain unchanged otherwise.
- No real participants, dummy contacts, call-history entries, call signaling, PeerConnection, ICE/STUN/TURN, network sender, recording of real calls or automatic upload. A PeerConnectionFactory used solely to obtain its actual local audio engine is allowed; it must create zero PeerConnection objects.
- Only Record or explicit live/echo-test start may open the microphone. Clip-only playback opens no input stream. No live microphone output through the earpiece or speaker; echo tests render only an independent clip. Optional live sent monitoring requires a confirmed wired/USB/Bluetooth headset and stops on route loss.
- First-build import: PCM16 or float32 WAV, one/two channels, 8/16/32/44.1/48 kHz. Preserve source bytes. At an int16 production boundary, any float-to-PCM working conversion is explicit in provenance, with saturation counts; it is not a lossless conversion. Use the existing quality converter for the separately labeled 44.1-to-48 kHz working stream.
- At most 120 seconds per take/import, 256 MiB total private lab files, one recording session and one writer thread. A recording session can contain four synchronized tap tracks; all share duration/storage limits. Never delete saved takes automatically to make space.
- Keep saved audio under app-private no-backup storage and outside Molly's conversation-backup/export paths. Unsaved captures are deleted on normal exit after confirmation, immediately retired on real-call preemption, and cleaned up after process death. No secure flash-erasure claim.
- Callbacks never perform file I/O, model loading, percentile calculation, JSON/string creation or blocking UI/settings reads. Use bounded queues and explicit gaps. No silent downmix, repeated stale speech, fabricated measurements or concealed fallback.
- Compact statistics refresh at most 10 Hz; process/thermal queries at most 1 Hz; expensive memory queries only with expanded diagnostics visible. Reset stats cannot reset overload protection or session-total errors.
- Active tests are foreground-only; background/lock stops capture and pauses playback. Rotation retains one session, never duplicates capture; process recreation never resumes recording automatically.
- Distinguish Configured, Measured, Estimated and Unavailable latency. Both-mode streams are parallel: do not add their delays or count inference time twice. No network RTT exists in a local lab.

## Review Focus

1. A real-call offer or group pre-join arrives while lab factory construction is completing; the late factory must not acquire capture, restore routes, install recording taps or Apply stale settings. Tasks 1–3 barrier tests.
2. The selected route reports success but active input/output changes later, or hardware capture effects settle slowly. Segment the take and report confirmation/warm-up instead of assigning old-route audio to the new route. Tasks 3–4 tests.
3. Four recording taps use different rates, start times and denoiser delays; writer stalls must not shift source alignment or turn missing blocks into valid audio. Tasks 4–6 tests.
4. A corrupted float WAV, oversized RIFF chunk, interrupted export or full storage must not abort the app or leave a complete-looking file. Task 4 parser/publish tests.
5. Closing or rotating the settings sheet can implicitly invoke existing save callbacks; a lab sheet must never call production save/reset, even when a model load completes late. Tasks 1 and 7 tests.

## Source findings and endpoint decision

The audited production path is `CallManager.createPeerConnectionFactory` → selected ADM → `WebRtcVoiceEngine` → `AudioState` → `AudioTransportImpl`. The latter already contains the sent/received denoising hooks. Its captured audio passes through WebRTC capture processing and any existing asynchronous processor before `SendProcessedData`; received audio passes through the existing receive adapter before the reverse-stream echo reference.

Use that real audio-engine instance. Add a lab construction scope to the existing factory handshake and register a narrow endpoint from its AudioState on the factory's worker queue. Attach a local `AudioMixer::Source` and a local `AudioSender`, start/stop that instance's ADM through gated local methods, and never create a network call. The normal AudioState only starts devices for registered streams; simply constructing a factory and drawing the call screen is insufficient.

Keep all WebRTC/RingRTC edits in a fail-closed patcher over `webrtc=7778d` (`63074e7b349aff7cc6d7ff46d4f1dd3101077242`) and RingRTC `v2.69.5-1`. A missing/ambiguous endpoint binding fails lab startup; it must not fall back to a generic AudioRecord/AudioTrack player or claim to exercise the call path. Task 2 must compile and test the actual patched sources before UI integration.

Route handling must also be scoped. The lab does not register a Telecom call; it uses the app's communication-device router. Where normal calls use Telecom, retain the same backend and effect policy but label the test as local routing rather than claiming Telecom signaling/lifecycle coverage. Do not change the production Telecom setting.

## File map and shared interfaces

Prefixes below expand to exact project directories:

- **A** = `app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/`
- **U** = `app/src/main/java/org/thoughtcrime/securesms/components/webrtc/v2/`
- **J** = `app/src/test/java/org/thoughtcrime/securesms/webrtc/audio/mock/`
- **I** = `app/src/androidTest/java/org/thoughtcrime/securesms/webrtc/audio/mock/`
- **N** = `native/mock-call/`; **T** = `tools/mock-call/`
- New app files live in `A/mock/`; shared adapters remain in A/U. No unrelated settings subsystem or global debug-flag enablement.

Shared contracts, owned by the tasks below:

- `LabToken(id: Long, generation: Long)` is local, nonzero and monotonic; tokens never appear in exported reports. UI/writer completions carry this token. `LabMode` is RECEIVED, SENT or BOTH; `Monitor` is RECEIVED or SENT; `Tap` is BACKEND_INPUT, BEFORE_SENT, AFTER_SENT or PLAYBACK_REFERENCE.
- `LabConfiguration(received: DenoiseSettings, sent: DenoiseSettings, effects: IncomingAudioSettings)` is immutable and versioned. `LabSection` selects RECEIVED_DENOISE, SENT_DENOISE or RECEIVED_EFFECTS for Apply. Monitoring, transient comparison bypass, stats and playhead position are not applied.
- `CallAudioSession` exposes `StateFlow<CallAudioUiState>`, `updateDenoise(Direction,DenoiseSettings)`, `updateEffects(IncomingAudioSettings)`, `setBypassed(Direction,Boolean)`, `retry(Direction)`, `reset(Direction)`, `save()` and `readStatus(): CallAudioStatus`. Production implementation delegates to current controllers; lab implementation never saves ordinary preferences.
- C++ `AudioScope` owns `DenoiseControl`, received `SettingsBus`, received reset/meter state and numeric diagnostics. Production uses its existing process-lifetime scope. The lab owns a separate scope for the lifetime of its actual AudioState. Share only immutable model data and the checked engine factory/budget, not mutable processing state.
- `LabCoordinator.open(): LabToken?`, `preemptForRealCall(): CompletionStage<Void>`, `check(LabToken): Boolean`, `close(LabToken)` and `applyToCalls(LabToken,LabConfiguration,Set<LabSection>): ApplyResult` serialize authorization with real-call startup. Open returns no token if a real call is incoming, outgoing, pre-joining, connecting or connected.
- RingRTC `MockCallSession.create(Context,AudioConfig): MockCallSession` holds an audio-only factory and checked endpoint handle. It provides asynchronous start/stop/route-reset requests and a synchronous nonblocking `revoke()` primitive. JNI handles are registry IDs validated by generation, never dereferenced arbitrary Java-supplied pointers.
- Native `LabEndpoint` accepts `Configure`, `Start`, `Stop`, `Seek`, `SetMuted`, `ReadStatus` and `DrainTap`; all commands are owner-scoped. It owns fixed source/tap rings and attaches only to lab AudioState. Callback interfaces borrow spans for the duration of the callback; queued packets copy into owned bounded storage.
- `LabAudioPacket` includes token/generation, route segment, tap, format, source-frame position, monotonic capture/render timestamp, configuration revision and validity/gap flags. Preserve native per-tap format; no file alignment by arrival time.
- `LabStatsSnapshot` is version 1 with separate Received/Sent statistics, validity and sample count/window, route/tap metadata and session-total faults. JNI reports small numeric records; Kotlin formats labels and JSON.

## Task 1: Isolated settings and production-priority ownership

**Create:** `A/CallAudioSession.kt`, `A/mock/LabConfiguration.kt`, `A/mock/LabCoordinator.kt`, `N/scope.{h,cc}`, `N/tests/scope_test.cc`, `J/LabConfigurationTest.kt`, `J/LabCoordinatorTest.kt`, `T/test_host.sh`.
**Modify:** existing CallDenoiseController/IncomingAudioController only to expose coherent snapshots and explicit selected-section Apply; `native/call-denoise/{service,transport}.{h,cc}`, `native/incoming-audio/adapter.h`; preserve production defaults.
**Produces:** the scope, session, token and configuration interfaces above. Lab scope creation must not create an ADM, load a model or change production controls.

- [ ] Write failing tests `lab_edits_leave_both_preference_files_identical`, `lab_native_controls_do_not_change_production`, `discard_and_crash_need_no_restore`, `apply_selected_sections_only`, `stale_apply_after_preemption_rejected`, `concurrent_apply_is_one_configuration_revision` and `real_call_wins_during_lab_open`. Assert production native meters/reset epochs remain separate too.
- [ ] Run tests RED, then implement explicit scopes with immutable configuration snapshots and owner-checked Apply. Synchronize Apply and production startup through one coordinator; publish selected native settings as one transaction without persisting transient bypass. Store lab presets separately, schema version 1; reject unknown schema/invalid values.
- [ ] Add a production-reservation guard before call-start processing, without writing account/recipient state. Integration with every concrete start route is completed in Task 3. A stale close can retire only its own scope.
- [ ] Run `bash tools/mock-call/test_host.sh scope` with ASan/UBSan and separately TSan; run `./gradlew :app:testProdStoreDebugUnitTest --tests '*mock.LabConfigurationTest' --tests '*mock.LabCoordinatorTest'`. Expected: all isolation/race assertions pass and baseline production tests remain green.
- [ ] Commit `feat(audio): isolate mock-call settings and session ownership` with only Task 1 source/tests/harness.

## Task 2: Actual native call endpoint, without a network call

**Create:** `N/endpoint.{h,cc}`, `N/local_source.{h,cc}`, `N/local_sender.{h,cc}`, `N/java/MockCallSession.java`, `N/mock_call.rs`, `N/tests/endpoint_test.cc`, `T/patch_ringrtc.py`, `T/test_patch.py`, `.github/workflows/mock-call-native.yml`.
**Modify through patcher:** RingRTC CallManager factory construction and Rust JNI registration; WebRTC `audio/audio_state.{h,cc}`, `audio/audio_transport_impl.{h,cc}`, `audio/BUILD.gn`; scope-selection plumbing in the existing denoiser/receive adapters. Patch after the current DeepFilterNet patch, never instead of it.
**Consumes:** AudioScope and LabToken. **Produces:** MockCallSession/LabEndpoint tied to the same factory's actual AudioState, ADM, APM, mixer and native effect hooks.

- [ ] Write failing real-source tests `local_factory_creates_zero_peer_connections`, `one_endpoint_matches_factory_scope`, `clip_playback_never_starts_adm_recording`, `capture_traverses_apm_then_sent_hook`, `received_traverses_mixer_effects_reverse_reference`, `sent_monitor_has_no_received_effects`, `production_factory_rejects_lab_commands`, `closed_endpoint_rejects_late_command`, `configuration_matches_production_backend`. Use fake ADM only to test scheduling/counters; separately compile the actual Java and Oboe configurations.
- [ ] Extend the factory handshake to carry an explicit AudioScope; expose a lab-safe factory constructor reusing CallManager's existing configuration, including hardware-effect requests. Register AudioState with a narrow endpoint and its actual worker queue; posted commands recheck lifetime/token. Hold the factory until all accepted callbacks stop and endpoint retirement completes.
- [ ] Add a local mixer source and local AudioSender, with no RTP/encoder/network sender. Register them only for lab scope; drive capture and playback through that AudioState's ADM. Add a bounded replay entry that invokes the existing sent-processing hook after APM for already-captured clips. Preserve any production asynchronous processor and capture provenance.
- [ ] Use the real receive adapter for Received processing. For Sent audition, select the actual sent result before the echo-reference tap and bypass received DSP for that monitor stream only. Both mode still processes Received when its result is not audible. Add taps before final PCM conversion for float exports; do not obtain “after DeepFilterNet” by rerunning the algorithm elsewhere.
- [ ] At this stage use memory-only synthetic inputs/sinks. Run `python3 tools/mock-call/test_patch.py`, `bash tools/mock-call/test_host.sh endpoint`, all existing denoiser/DSP tests, and the patched ARM64 native-build workflow. Expected: actual native compilation/retained ABI plus native hook counters and signal comparisons pass; no UI-only or source-anchor substitute.
- [ ] Commit `feat(audio): add a local endpoint to the real call transport`. Preserve successful native artifact/input hashes for later packaging; no APK is delivered at this stage.

## Task 3: Real handset/speakerphone routing and safe preemption

**Create:** `A/mock/LabRouteController.kt`, `A/mock/LabLifecycle.kt`, `J/LabRouteControllerTest.kt`, `I/LabRouteInstrumentationTest.kt`.
**Modify:** `A/SignalAudioManager.kt`, its API31 routing implementation, `service/webrtc/SignalCallManager.java`, `N/endpoint.{h,cc}` and native mock gate tests. Route commands gain optional ownership guards; production remains the default.
**Produces:** confirmed route segments and foreground-only hardware acquisition; real-call priority across app and native lifetimes.

- [ ] Write failing tests `requested_route_is_not_confirmed_route`, `handset_means_communication_earpiece`, `same_input_id_does_not_fake_route_equivalence`, `late_route_ack_is_ignored`, `record_route_change_creates_new_segment`, `headset_disconnect_closes_live_monitor`, `old_stop_cannot_restore_mode_over_real_call` and `permission_loss_cannot_be_reopened_by_retry`.
- [ ] Reuse current communication-device selection and `RingRtcDynamicConfiguration.getAudioConfig()` without enabling internal flags. Confirm the active backend input/output before recording a stable segment. Stop old recording/injection gates immediately on route replacement, stop the backend input stream on its owning queue and discard pending old-stream packets; resume only after route confirmation and capture restart with a new permitted segment and fresh samples. A failed route request stays visibly failed.
- [ ] Wire production reservation/preemption before `startPreJoinCall`, outgoing audio/video, accepted incoming offers and authoritative incoming/group-start callbacks. Also guard native factory/session acquisition and shared audio-focus/router acquisition so a missed UI path cannot attach lab audio to a real call. Release a reservation when the real call action ends/rejects with no active call.
- [ ] Revoke lab taps/sources first; asynchronously stop hardware on its owning queue, then retire models/writers off callbacks. Real-call setup must not wait for file exports, user confirmation or model inference/loading. Use a two-second resource-stop watchdog; on failure report acquisition failure and keep the lab revoked, never transfer its live endpoint to production or silently claim the new call is healthy.
- [ ] Background/lock stops input and output; rotation keeps one coordinator, not one per composable. A visible live microphone always has an explicit current permission/authorization record. Echo mode locks audible output to its independent received clip; choosing Sent ends live capture before recorded audition.
- [ ] Run route/coordinator JUnit tests, deterministic native preemption tests with barriers, and Android route instrumentation. Expected: captured sentinel samples cannot cross owners or route segments, and production cleanup/reacquisition is ordered. Mark hardware routes unavailable on an emulator as NOT TESTED, not passed.
- [ ] Commit `feat(audio): route mock calls through handset and speakerphone safely`.

## Task 4: Synchronized recording taps, bounded storage and WAV import

**Create:** `N/tap_queue.{h,cc}`, `N/tests/tap_queue_test.cc`, `A/mock/{LabTake,LabTakeStore,LabWaveCodec,LabWriter}.kt`, `J/{LabWaveCodecTest,LabTakeStoreTest}.kt`, `T/test_recording.py`.
**Modify:** lab-only hooks in actual AudioTransportImpl and selected backend diagnostics adapters; app backup exclusion rules only where no-backup placement is insufficient.
**Produces:** immutable takes with four optional tap tracks and version-1 metadata. One writer services separate fixed-capacity queues; no extra recorder session.

- [ ] Write failing tests for PCM16/float32 round trips, RIFF odd padding/chunk order, truncated header/payload, NaN/Inf, oversized arithmetic, >2 channels, unsupported rates, 120-second limits, 256-MiB aggregate reservation, disk-full/queue-overflow and interrupted finalization. Test that a failed take is never published as complete.
- [ ] Tap backend input before WebRTC processing, immediately before sent DeepFilterNet, actual float sent output and the audible digital playback reference. Each tap validates owner/mute/generation before accepting data. Stamp source intervals, not callback arrival order. Expose an inaccessible tap as unavailable; never reconstruct it from a later tap.
- [ ] Bound each tap queue to 32 ten-millisecond packets. Processed taps reuse existing <=480-frame/two-channel limits. The earliest backend tap allows <=1920 frames/two channels per 10 ms, with actual rate/format metadata; larger shapes make that tap unavailable rather than downsample it. Enforce size checks before copies and use one control/IO writer thread. Any overflow marks a gap and stops the take with an incomplete flag.
- [ ] Preserve the original PCM representation in source WAV; write processed float output to float32 WAV. Import originals byte-for-byte. Build explicitly labeled working copies for resampling/production PCM boundaries; preserve originals and record all working conversions. No automatic reuse of a processed export as source without confirmation.
- [ ] Use `noBackupFilesDir/mock-call/` with temporary/saved separation and bounded atomic manifests; never open a public path for recording. Save/Delete/Export are explicit. On preemption, retire unsaved files without a dialog; startup removes abandoned temporary files. Export source, processed result, settings and report as separate selections through SAF; confirm grants and abort safely on revoked destinations.
- [ ] Run `bash tools/mock-call/test_host.sh recording`, JUnit WAV/store tests and Android SAF/permission tests. Expected: byte-accurate supported originals, timestamp alignment, explicit gaps and no post-revocation acceptance. Files accepted before revocation may already exist on storage; do not promise to erase previously recorded history at mute.
- [ ] Commit `feat(audio): record synchronized call-path takes without lossy export`.

## Task 5: Replay, selections, A/B and microphone-route comparisons

**Create:** `N/playback.{h,cc}`, `N/tests/playback_test.cc`, `A/mock/{LabPlaybackController,LabComparison}.kt`, `J/LabComparisonTest.kt`.
**Consumes:** endpoint/take/configuration/route contracts. **Produces:** real-time paced Received/Sent/Both replay, selection/loop/restart and immutable comparison results.

- [ ] Write failing tests `sent_replay_does_not_repeat_apm`, `both_streams_are_independent`, `monitor_switch_keeps_other_processor_running`, `seek_never_emits_old_epoch`, `same_snapshot_same_preroll`, `short_selection_and_eof`, `original_compares_whole_selected_path`, `settings_ab_not_route_ab`, `export_freezes_configuration` and `handset_speakerphone_audition_uses_fixed_output`.
- [ ] Pace playback with the active ADM render clock, not an unbounded file-processing loop or UI timer. Sent replay enters at the pre-denoiser boundary through the actual sent hook; no input device opens. Both mode owns one source per direction and a single monitor selection. Processed results use represented source intervals; underrun uses the matching original or labeled silence, never a previously emitted block.
- [ ] Add Play/Pause, selection start/end, Loop and Restart. Every source/seek/loop/model change invalidates earlier work. Run up to 1000 ms deterministic pre-roll using available earlier source and zero padding before the beginning; do not play or export pre-roll. Wait for model readiness; show Warming up. Original/processed and A/B keep alignment instead of switching to current undelayed audio.
- [ ] Store complete A/B LabConfiguration snapshots and run them sequentially with the same selection/pre-roll. Keep route takes separate: Handset versus Speakerphone recording workflows allow Fixed position or Typical use, require confirmed actual routes and record the procedure/volume. First-pass route capture defaults to added effects off without altering production settings.
- [ ] Compare completed route takes through a fixed output after stopping capture, with received effects bypassed for sent audition. Optional loudness matching derives one fixed monitor gain from each completed interval, defaults Off and uses monitor-only peak protection. It must not change saved levels, exports or live acoustic-test volume.
- [ ] Run `bash tools/mock-call/test_host.sh playback`, real-model repeatability/reference tests and comparison JUnit tests. Expected: matching source intervals and deterministic tolerances, no cascaded sent/received effects and no artificial route-quality score from differently spoken takes.
- [ ] Commit `feat(audio): add repeatable call-path replay and route comparisons`.

## Task 6: Visible statistics and defensible latency measurements

**Create:** `N/stats.{h,cc}`, `N/tests/stats_test.cc`, `A/mock/{LabStatsSnapshot,LabStatsRepository,LabReport,LabLatencyTest}.kt`, `J/LabStatsTest.kt`.
**Modify:** existing worker instrumentation to accept an optional lab-only numeric observer; backend Java/Oboe timestamp adapters through the patcher. Production with no observer performs no new timing/history collection.
**Produces:** the version-1 stats contract and source-aligned route/settings summaries; display components are Task 7.

- [ ] Write fake-clock tests for stale/foreign tokens, pause/resume, route and clock discontinuities, percentile sample windows, empty data, reset-versus-session totals, zero samples/silence, peak overs versus int16 saturation, and deadline eligible denominator. Assert resetting stats cannot reset overload.
- [ ] Collect before/after peak and RMS per channel, saturation/over counts, exposed compressor/limiter reduction, model-estimated SNR, callback size/rate/channels, worker latest/mean/p95/max, eligible misses/fallback reasons, queue occupancy/high-water, writer backlog/gaps and actual hook counters. Use the latest 500 valid processed blocks for a five-second window at 10 ms/block; reset/mark the window on segment changes. Nearest-rank p95 uses index ceil(0.95*n)-1; compute off callback.
- [ ] Use a maximum 100-ms snapshot publication cadence. On mute/error/preemption critical status updates remain visible even with Pause display active. At most 1-Hz process CPU/thermal/RAM sampling; report app scope and supported readings only. Expensive memory queries require expanded diagnostics. A stale window never reads as Active/zero delay.
- [ ] Report Configured processing/source alignment separately from Measured inference/queue wait. Prove observed hook-to-hook offset using source intervals. Stable Off means zero additional DSP delay; aligned bypass retains delay. Do not sum parallel streams or add inference time to a fixed allowance that already includes it.
- [ ] Query real-backend frame-position/monotonic timestamps off callback. Validate stream identity, units and clock epochs; derive endpoint-specific Estimated input/output delay only from valid pairs. Mark unavailable hardware/OEM metadata Unknown/Unavailable; never change the backend to produce a number. Network fields are Not applicable.
- [ ] Implement an explicit optional local reference test using an independent bounded speech-band probe at unchanged user communication volume; correlate playback reference and captured tap over <=500 ms lag. Require at least five consistent valid matches, normalized correlation >=0.7, an unambiguous peak and <=5-ms median-absolute spread; otherwise show No reliable measurement. Synthetic tests cover known 20/80/200-ms delays, silence, suppression, clipping, drift and ambiguous repeated signals. Labels include taps, route, processing and acoustic geometry; this is not network latency or calibrated acoustic SPL.
- [ ] Run native statistics tests, JUnit formatter/report tests and known-delay native fixtures. Expected: true methods/units/validity, no duplicated delay, no callback allocations, reset preserves faults and no guessed Pixel values. Export human-readable text plus numeric JSON without audio or identifiers unless a separate audio export is selected.
- [ ] Commit `feat(debug): expose call-path statistics and measured latency boundaries`.

## Task 7: Shared call-screen presentation and lab controls

**Create:** `A/mock/MockCallLabActivity.kt`, `U/{MockCallScreen,LabTransportControls,LabStatsPanel,LabDiagnosticsSheet}.kt`, `app/src/main/res/values/mock_call_lab.xml`, `I/MockCallLabUiTest.kt`.
**Modify:** `U/{CallScreen,CallControls,CallAudioControls,IncomingAudioControls}.kt`; HelpSettingsFragment; app AndroidManifest. If a small shared presentation value is required, create `U/CallScreenIdentity.kt`; never fabricate a Recipient.
**Consumes:** scoped CallAudioSession, playback, take, route and stats contracts.

- [ ] Write UI tests for LOCAL TEST label, navigation, non-exported entry, no microphone on open/play/import, route confirmations, recording timer, live stats visible while sliders scroll, independent tabs, sheet back/close/rotation, large font/landscape, A/B versus route take labels, explicit Apply selection, Save/Delete/Export and ordinary-call regression. Assert lab close/save cannot call singleton save.
- [ ] Add **Settings → Help → Debug → Mock Call Lab** without requiring internal-user flags or making the APK debuggable. The Debug entry contains only this lab. Reuse production call-screen layout/control components with local display identity and local callbacks; do not launch real-call actions or remote-only camera/invite/safety-number controls.
- [ ] Parameterize shared Call audio/received-effects content by CallAudioSession, defaulting to the production adapter. Preserve current ordinary-call behavior and preference keys. Lab session.save() saves no production data. Display Received and Sent controls with their existing numeric ranges, presets, bypass/retry and reset.
- [ ] Place the compact stats panel below the local-test header and above transport/call controls, outside scrollable settings; keep a summary in the audio sheet. Include actual route/tap, state, configured delay, measured block time, misses and meters for each direction. Expanded diagnostics adds Pause display, Reset stats and Export report. Reflow instead of covering mute/stop/hang-up.
- [ ] Show Handset/Speakerphone take comparison and explicit Echo test. Never allow live sent output on speaker/earpiece. Do not hide an unavailable model/tap/route behind an enabled-looking control. Keep AMOLED surfaces/outlines and accessible text/icons, not color-only warnings.
- [ ] Run `:app:connectedProdStoreDebugAndroidTest` on an available emulator, the focused app unit tests and `python3 tools/amoled/test_theme.py`. Expected: actual rendered controls and navigation pass. If no instrumentation device is available, report NOT RUN; host/source checks cannot replace rendering evidence.
- [ ] Commit `feat(ui): add the Mock Call Lab with pinned live stats`.

## Task 8: Complete release, regression checks and same-key signing

**Create:** `.github/workflows/mock-call-lab.yml`, `T/{verify_release,test_release}.py`, `docs/mock-call-lab-validation.md`.
**Modify:** native builder/override/package checks and `app/build.gradle.kts` for verified version 171907; preserve the rest of the release identity.
**Produces:** actual patched AAR, complete optimized APK, input manifest, test artifacts and signed update report.

- [ ] Write package tests rejecting old/stock AARs, missing local-endpoint JNI, debug/exported recording components, changed models, wrong package/certificate, stale version, absent stats UI/resources and missing notices. Verify model assets survive packaging exactly once and retain original gzip archive names; include the existing .tar alias finalization regression.
- [ ] Run red/green package checks and all new host/unit tests plus existing DSP, denoiser, theme and native gate tests. Build with pinned NDK 28.0.13004108, JDK21, build-tools36.0.0 and platform android-36.1. Native cache keys cover all scope/endpoint/tap/patch inputs, not just the previous denoiser. Never reuse the 171906 AAR as a lab-capable artifact.
- [ ] Build/check native JNI on both Java and Oboe configurations; preserve the current runtime `.so` only after an exact runtime-source/lock match. Run actual native source/sink/tap sentinel tests with the packaged AAR. Run packaged ARM64 inference under Android Bionic as before, clearly distinguished from physical audio routing.
- [ ] Run `:app:lintVitalProdStoreRelease` and `:app:assembleProdStoreRelease` separately with the proven one-worker, no-parallel, in-process Kotlin and 12-GB heap build settings. Use the verified native override and original package/title arguments. Preserve failure logs and exact commits/artifact hashes; no fabricated APK link or renamed prior release.
- [ ] Download only the exact matching complete artifact, verify its ZIP digest and compiled package/resources/JNI/models/ELF code. Sign offline with the original private key; verify v2/v3, certificate equality with the supplied 171906 APK, increased version and 16-KB ELF geometry. No credential reaches GitHub or a log. Missing original key blocks signing; never replace it.
- [ ] Perform a separate whole-branch review against v3 and this plan. Use a separate reviewer when available, otherwise identify it as self-review. Resolve privacy/path-fidelity failures before a test release. Confirm every non-optional v3 feature has implemented code and evidence; do not describe a partial player as the finished lab.
- [ ] Commit build/validation source only and deliver the signed test APK with verified entry path, version, test results and explicit remaining physical-device limits. No automatic merge or GitHub upload of private recordings/credentials.

## Task 9: Physical-phone acceptance

**Update:** `docs/mock-call-lab-validation.md`; retain only non-sensitive numeric evidence.

- [ ] Install over 171906 without uninstalling; confirm existing account/messages, AMOLED and ordinary received/sent settings. Confirm lab edits/discard/rotation leave those settings unchanged and Apply changes only selected sections.
- [ ] Capture distinct handset/speakerphone takes at fixed geometry and typical-use geometry. Inspect all observable taps, actual route/format metadata and preserved levels; a small/no difference is a valid observation. Test simultaneous independent speaker playback and microphone capture, speech-only/playback-only/double-talk, then stopped-capture sent audition.
- [ ] Exercise Received, Sent, Both, Standard/Low Latency and mixed models; selections, looping, A/B, source provenance, exports, live stats while tuning, pause/reset/report and any reliable local latency measurement. Verify real factory/hook counters and non-cascaded sent audition.
- [ ] Preempt during recording, playback, model load, route change and export with an actual incoming/outgoing call. Test microphone privacy, mute/unmute, lock/background, headset removal, storage failure and repeated start/stop; no lab audio may reach a real call and no real call audio may reach a lab file.
- [ ] Run sustained Both-mode/echo tests for at least 20 minutes per active model pairing, record numeric deadline/queue/thermal/memory trends and UI overhead, and check available Bluetooth/USB routes. Report unavailable tests honestly. Production readiness requires these results, not merely successful compilation.

## Verification and continuation record

Create a git-ignored ledger at `.superpowers/sdd/2026-10-02-mock-call-lab/progress.md` during execution, beginning with this plan path. Record task base/head commits, failed-first and final commands/results, deviations and the next incomplete test. Check the remote branch and existing ledger before continuing; do not rebuild finished denoiser tasks or repeat design approvals.

Dependency order: Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7 → Task 8 → Task 9. Run long native builds in GitHub Actions and inspect their actual results; do not promise unattended development or describe an in-progress run as successful. If independent agents are unavailable, execute inline and say so for review evidence rather than inventing a reviewer.

### Source references

- Owner specification v3 and `docs/deepfilternet-release-171906.md` at the source baseline above.
- App `service/webrtc/{SignalCallManager.java,RingRtcDynamicConfiguration.kt}`; `webrtc/audio/{SignalAudioManager.kt,CallDenoiseController.kt,IncomingAudioController.kt}`; `components/settings/app/help/HelpSettingsFragment.kt`; shared call-screen/controls files listed in Task 7.
- Molly WebRTC at 7778d: `audio/audio_state.{h,cc}`, `audio/audio_transport_impl.{h,cc}`, `media/engine/webrtc_voice_engine.cc`, `sdk/android/src/jni/audio_device/{java_audio_device_module,oboe_audio_device_module}.cc`.
- Android communication-route contract: https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)
- Device-latency measurement boundaries: https://developer.android.com/ndk/guides/audio/audio-latency and https://google.github.io/oboe/classoboe_1_1_audio_stream.html

**Handoff:** Review this written implementation plan before product implementation. Inline execution with GitHub Actions is the available route; no independent coding/review subagent is assumed. This plan does not claim a new APK, a device recording or a latency measurement.
