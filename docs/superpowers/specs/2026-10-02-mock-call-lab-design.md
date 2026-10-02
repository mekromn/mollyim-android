# Molly Audio — Mock Call Lab design

**Date:** 2026-10-02  
**Status:** Revised specification for owner review, including real microphone/speakerphone capture, handset-versus-speakerphone comparison, and visible live stats with separately labeled latency measurements. The in-chat design was approved; this document does not claim implementation or a new APK.  
**Repository / branch:** `mekromn/mollyim-android` / `feature/mock-call-lab`.  
**Source baseline:** `3935ddf30aecd90f19a414095a9b78a549d1a146`, the delivered DeepFilterNet release checkpoint.  
**Installed baseline:** Molly Audio 171906. Preserve `com.mekromn.mollyaudio`, ARM64, minSdk 27, targetSdk 35, optimized release packaging, and the original signing certificate. Reserve 171907 for the next update; recheck the installed/latest supplied version before signing.

## 1. Purpose and success criteria

Record a sample once, replay or loop it through the real call-processing paths, adjust the existing call-audio controls while listening, and deliberately apply the preferred settings to ordinary calls. Testing needs no second phone or connected participant. The lab must exercise actual native processing and communication-device routing, not just imitate the call screen around a separate media player.

The approved scope includes Received, Sent, and Both modes; physical microphone recording in handset and speakerphone routes, simultaneous communication-output playback and microphone capture, route-to-route recording comparison, and lossless sample import; playback, loop selection, original/processed comparison, two settings snapshots A/B, explicit save/export, on-screen live statistics with expandable diagnostics and latency breakdowns, and temporary settings. It preserves DeepFilterNet Standard/Low Latency, received EQ/compressor/gain/limiter, AMOLED, and the signed-in account.

This is a new local-session subsystem. The written implementation plan must map ownership and test injection into the actual native backend; calling only the standalone denoiser is insufficient. Network/codec simulation, recording real conversations, and a new denoising algorithm are outside this change.

## 2. Entry and call-screen behavior

Provide **Debug → Mock Call Lab**, available in the optimized signed app without making the APK debuggable or enabling unrelated internal flags. The entry must be internal/non-exported and unavailable while a real call is incoming, outgoing, connecting, or connected. The implementation plan will locate the existing settings/debug navigation and add only the entry needed here.

Open the production call-screen presentation and shared audio controls with a permanent **LOCAL TEST — Nobody is connected** label. Do not create a dummy contact, Note to Self call, call-history record, or fake server-connected state. Where the current presentation requires a real Recipient, introduce a local display model rather than modifying the recipient database. Real-call listeners must not be installed on this screen.

Preserve the layout of route, microphone-mute, hang-up, and Call audio controls. Hang-up ends the local session. Camera, screen sharing, participant invitations, safety-number actions, and other remote-only actions are absent. Show Record, Stop, Play/Pause, Restart, Loop, a position/selection control, the selected source, and its recording/import provenance. Keep independent Received and Sent settings tabs and scroll positions.

A route label distinguishes requested output from the actual communication output and input reported by the backend. Unsupported routes produce a visible error rather than a successful-looking button. Preserve black surfaces, readable accents, thin AMOLED outlines, and accessibility descriptions for transport state and values.

## 3. Audio paths and what each mode proves

Use a local native endpoint built around the same WebRTC audio transport, capture processing, mixer, selected Java/Oboe audio-device backend, and communication routing policy as production calls. Add bounded test-source and local-sink interfaces; do not duplicate the DSP implementations. Source substitution and recording taps exist only on an explicitly owned lab session. No peer connection, ICE/STUN/TURN exchange, Signal call-signaling action, or network sender is required.

| Mode | Signal path and audition |
|---|---|
| Received | Clip source at the local receive mixer → received DeepFilterNet → received EQ/compressor/gain/limiter → reverse-stream echo reference → normal playback conversion and communication output. No microphone is needed for clip-only playback. |
| Sent recording | Explicit microphone recording through the selected call-capture backend and existing WebRTC capture processing; tap immediately before sent DeepFilterNet. Store that input as **Before DeepFilterNet**, not Raw microphone. Sent processing can be tested live without speaker monitoring; the recorded source remains unmodified by the added denoiser. |
| Sent replay | Stored Before DeepFilterNet sample replaces input at that same pre-denoiser boundary; run the real sent hook, gates, workers, and local sink, without repeating capture echo cancellation or built-in suppression. Audition sent output through communication playback with received DeepFilterNet and received effects bypassed for this monitor stream only. |
| Both | Independent source streams drive sent and received processors concurrently. Default to two clips with the microphone closed; optionally select explicit live microphone capture on the sent side. Both processors keep running regardless of which direction is auditioned. Select one monitored output; do not silently cascade sent processing into received processing. |

The monitor selection occurs before the existing playback echo-reference tap, so that reference follows what is actually rendered. When auditioning sent-only, the received effects do not process the monitor. This is a local monitoring branch, not a change to ordinary calls. Both mode is a two-processor load test; when a processed receive stream is not audible, report that fact rather than claiming its output is the acoustic reference.

Show whether input is live capture, a post-capture-processing recording, an imported clip, or a previously processed result. Replaying a captured clip does not re-test acoustic echo cancellation against its original room: its capture processing is already baked in. Live capture with receive playback exercises more of the acoustic path, but cannot reproduce a remote person's device or network. Do not claim codec fidelity, whole-call latency, or remote intelligibility from a local PCM loop.

### 3.1. Real microphone paths: normal handset versus speakerphone

**Owner additions, 2026-10-02:** recording must use the real microphone and speakerphone call paths, and the lab must expose the difference in microphone recording between speakerphone and normal call mode. These are mandatory first-build requirements, not optional file-replay simulations.

Provide **Normal call — Earpiece/handset** and **Speakerphone** as explicit capture-route choices on the mock call screen. Normal means the ordinary Molly earpiece call route, not Android `MODE_NORMAL`, a generic recorder, cellular `MODE_IN_CALL`, or an invented microphone preset. Both choices use the production communication-mode policy and selected Java/Oboe backend, including its capture source/input preset, hardware-effect requests, software echo cancellation, built-in noise suppression and automatic gain policy. Selecting Speakerphone must actually request the communication speaker output and allow the platform's paired input policy to apply; changing only a playback volume or UI flag is insufficient.

Start a new capture segment only after the requested route and active stream have been checked. Report the requested route separately from the output/input actually reported by Android and the backend. A route change during recording closes the previous segment, invalidates queued old-route audio and starts a freshly tagged segment after confirmation. Mark routing/reinitialization intervals explicitly; do not present a mixed-route take as one uninterrupted, stable capture. Do not force an unrelated preferred input just to manufacture a difference. Identical reported input IDs do not establish identical processing or prove that the route request failed.

Expose **Compare microphone routes** as a two-take workflow. Record one live take in handset mode and another in speakerphone mode, using the same selected test sequence and added-effect settings. Keep these route takes distinct from the existing A/B *settings* snapshots. First compare with added DeepFilterNet and received effects off, then apply the same sent settings to each preserved pre-DeepFilterNet take. Audition both takes through one fixed chosen output after microphone capture stops, with received effects bypassed for sent-result audition. Default to unnormalized playback so capture-level differences remain audible; optional audition-only loudness matching is separately labeled and never alters saved samples or measurements.

Offer two labeled procedures: **Fixed-position route comparison** (keep phone, sound source, distance, orientation and volume constant) and **Typical-use comparison** (handset near the ear/mouth versus speakerphone at the user's chosen distance). The latter includes posture/acoustic differences and must not be reported as isolating an internal microphone setting. Repeating a spoken phrase is useful for listening but is not sample-identical input. For a repeatable route-only test, an external source may play the same speech/noise at fixed geometry; this is optional and does not make a second phone a prerequisite for the lab. Replaying the phone's own speaker into its microphone is an echo-path test, not a neutral microphone-frequency-response measurement.

During an explicitly started live take, provide separately selectable, time-stamped recording taps:

| Tap | Meaning |
|---|---|
| Call-backend microphone input | Earliest accessible input from the selected production audio backend, before WebRTC capture processing. It can already include Android/vendor hardware processing; never label it raw ADC or promise individual physical-microphone channels. |
| Before sent DeepFilterNet | After the existing call capture processing and immediately before the added sent denoiser. This remains the immutable default source for repeatable denoiser tuning. |
| After sent DeepFilterNet | The actual sent-stage result, with its represented capture interval, settings revisions, bypass/fallback and missing-block markers. No received effects are applied. |
| Playback reference | Optional digital feed at the existing echo-reference boundary for the output actually auditioned. This is the digital signal sent toward playback, not a measurement of sound emitted by the physical speaker. |

These taps are branches of the **same active lab capture/render streams**, not additional recorder sessions that could change the route or processing policy. Validate owner, permission, mute and generation at every recording tap, including the earliest input tap. Use separate bounded tap queues with one writer thread; align exported comparisons by timestamps and represented sample intervals, not callback arrival order. Keep actual rate/channel/sample formats and mark unobservable taps unavailable rather than synthesize them from later processed audio. Platform-reported active microphone count/channel mapping and visible effect state are diagnostic metadata only; unavailable OEM details must remain unknown, and changing backend just to obtain richer metadata is not an equivalent path test.

### 3.2. Simultaneous speakerphone playback and microphone recording

Add an explicit **Speakerphone echo test** under Both mode: an independent received clip passes through the real receive path and physical speaker while the real call-configured microphone records the room and the user's voice. Capture Before/After sent DeepFilterNet together when selected, and optionally the call-backend input and digital playback reference. Test playback-only intervals, speech-only intervals and the user speaking while the clip plays. Keep the production echo-cancellation/automatic-gain/noise-suppression policy; the goal is to hear residual echo and unwanted voice attenuation with the actual device path, not to disable protections to make the recording louder.

The loudspeaker plays **only the independent received test source**, never the live microphone or its processed sent result. In this test, lock audition to Received; selecting Sent audition pauses speaker playback and stops live capture before replaying the recorded result. This is distinct from the optional headphone-only live sent monitor. Allow the corresponding earpiece playback/capture test for comparison, but never automatically raise communication volume or apply loudness matching to a live acoustic test. Explicitly starting the test is required to open the microphone.

A microphone re-recording is the available acoustic observation. The digital playback-reference recording alone cannot establish speaker response or physical output level, and capture after hardware echo suppression cannot expose the unsuppressed room waveform. Do not claim measured Pixel mic selection, beamforming coefficients, raw microphone access, calibrated echo attenuation, or a route-quality ranking before device evidence exists. Numeric comparison may report observed speech/pause levels, peaks, clipping, spectral balance and residual playback-related energy with defined conditions; it must not label ordinary between-take differences as an exact transfer function or calibrated echo-canceller score.

## 4. Recording, files, and explicit exports

Only Record or an explicit live-input action opens the microphone. Opening the lab, editing controls, importing audio, and playing an existing clip must not do so. Request normal microphone permission, honor system microphone privacy, and show a persistent recording timer and indicator in the visible lab. Mute closes the capture/recording gate and invalidates buffered pre-mute audio; after unmute only new allowed samples may be recorded or processed. Muted time is represented by labeled silence, not retained pre-mute speech.

Record with a bounded callback-to-writer queue; file I/O, WAV header finalization, checksum calculation, and encoding never run on audio callbacks. Keep source PCM at the tap's actual rate, channel count, and sample representation. Processed exports use 32-bit float WAV to preserve floating-point DSP results; original exports preserve captured PCM without lossy compression. An export cannot claim extra precision for a source already captured as int16.

First-version import supports PCM16 and float32 WAV at 8, 16, 32, 44.1, or 48 kHz with one or two channels. Preserve the imported original. For 44.1 kHz, show that a separate 48 kHz working stream is required before playback through the native test hooks; use the production-quality converter, not downmixing or a lower-quality replacement. Reject other formats clearly rather than silently transcode. Reject malformed headers, non-finite samples, excessive sizes, and unsupported channel layouts before starting audio.

Product limits for the first build: 120 seconds per recording/import, 256 MiB total private lab files, and one active recording session serviced by one writer thread. A session may contain the explicitly selected synchronized tap tracks in Section 3.1; all tracks share the duration/storage limits. Enforce limits during streaming as well as at import inspection; explain a limit before starting when the size is known. Never delete saved clips automatically to make space. Writer overflow or disk failure stops recording, marks the captured portion incomplete, and retains working playback/control behavior. Do not disguise missing blocks as a valid continuous recording.

Clips start as temporary private lab data. Save keeps them after exit; unsaved captures are discarded when the session ends, with confirmation on an ordinary user exit. Real-call preemption never waits for that confirmation. App-start cleanup removes abandoned temporary captures. Saved lab audio is excluded from automatic backups/sync and remains until explicit deletion; no secure-erasure guarantee for flash storage is made.

Export source WAV, processed WAV, settings, and the diagnostic report as separately selectable items through the system document flow. Reports exclude audio by default. No export overwrites the original, uploads to GitHub, or automatically sends a message. Mark processed audio clearly and require confirmation before using it as a new source, to avoid accidental double denoising.

## 5. Repeatable audition, loops, and A/B

Keep the recorded/imported source immutable. Live slider edits use existing parameter-update behavior without saving to ordinary call preferences. Original/Processed compares the source at the selected path's input against its result; on Received, Original bypasses the whole received processing chain for audition, not merely DeepFilterNet. The existing DeepFilterNet-only comparison remains labeled separately.

Comparison uses matching source intervals and retained alignment delay. It must not jump to newer samples, replay stale worker output, or report retained delay as zero. For A/B, store two complete lab configurations: independent sent/received denoiser settings and the received effects. Run snapshots sequentially, not with extra simultaneous model contexts. Switching A/B restarts the same selection with identical reset and warm-up policy; selecting a different monitored direction does not stop the other processor in Both mode.

Seeking, restarting, changing sources, and loop boundaries create a new stream generation. Discard previous queued input/output and reset model/converter histories on their owning workers. Use up to one second of source pre-roll before a selection, with zero padding before the clip's beginning, to initialize each repeat consistently. Pre-roll is not audible or included in the selected exported duration. Wait for readiness rather than count model loading as an inference deadline miss. Label the short reset/warm-up transition instead of claiming an uninterrupted seamless loop.

Audition transitions may use short fades to avoid clicks. Fades and optional loudness matching are monitor-only: they do not alter source files, scientific comparison taps, exported processed results, or ordinary call settings. Loudness matching starts Off; when enabled, base it on a completed selected interval and disclose its audition gain and peak protection. Do not call it a calibrated hearing-safety limit.

Rendered exports identify the settings used. Do not assemble a single export from portions produced under different live edits without marking it as such; the default processed export is one complete pass with a frozen configuration. Record failures, overload bypass, or missing intervals in export metadata and mark the result incomplete rather than silently labeling it fully processed.

## 6. Settings must remain sandboxed

Initialize the lab from copies of current received effects and sent/received DeepFilterNet settings. Lab controls, reset, bypass, presets, A/B snapshots, and exit must not persist to `call_denoise_v1` or the existing incoming-effects preferences.

The current Call audio UI uses singleton controllers, and the native service owns a shared control object. Parameterize the shared UI and processing configuration by an explicit session scope, with the existing production controllers as the ordinary-call default. Give lab processing its own mutable controls and meters while retaining the same DSP code and model bytes. A temporary global override followed by restore-on-exit is not acceptable: a crash or real-call interruption could leave it active.

**Apply to calls** shows which sections will be copied, then publishes one coherent validated configuration through the existing persistent controllers. Applying does not enable the microphone or start a call. Include only requested configuration, not transient bypass, monitoring choice, playback position, or diagnostic state. If a real call begins, reject a stale lab Apply action after ownership changes. Close without Apply leaves everyday settings unchanged.

Saved lab presets are versioned local JSON without audio or account identifiers. Unknown versions and invalid settings fail safely. Reuse existing validation, model choices, and ranges; do not quantize weights, change denoising arithmetic, or alter ordinary echo-cancellation/suppression policy.

## 7. Real-call priority, mute, and resource ownership

Implement an explicit production/lab ownership boundary across the call coordinator, recording taps, injected sources, device routing, and native transport gates. A real incoming/outgoing call preempts the lab before it can bind audio resources. An already active real call prevents lab startup. Ordinary background messaging/network connections remain outside this local test; the lab itself sends no call signaling or audio.

On real-call arrival, hang-up, permission loss, or session replacement: atomically disable the lab recording/injection/monitor gates, advance its generation, invalidate queues, and retire native workers/resources off callbacks. A delayed model completion, file-write callback, or UI action cannot re-enable the retired owner. Old cleanup must not restore an obsolete route, audio mode, microphone state, or focus over the new real call. Preemption does not wait for exports, user dialogs, or model loading. Real-call setup must never receive a lab clip or a lab recording tap.

Record-then-play is the default. Simultaneous received-clip speaker playback and microphone recording is explicitly supported by Section 3.2 and is not live microphone monitoring. Optional live sent-audio monitoring requires a verified wired, USB, or Bluetooth headset output and an explicit enable action. Earpiece and speakerphone are not treated as safe headset monitoring routes. Headset removal disables monitoring before any fallback speaker route and requires deliberate re-enabling. No automatic live microphone feedback through the speaker is allowed. Received clip playback through speakerphone remains available.

The first build runs active tests only while the lab is visible: leaving it or locking the screen stops recording/live monitoring and pauses playback, releasing lab audio resources. A configuration change such as rotation preserves the session without duplicating capture; process death never auto-resumes recording. Closing releases temporary audio buffers, native contexts, focus, route requests, and handles. A corrupt model or denied microphone must not make the rest of Molly unusable.

## 8. Live stats, latency, and proof of the exercised path

**Owner addition, 2026-10-02:** useful statistics and latency must be visible on the mock call screen while testing, not only in an exported report. This is part of the first-build interface.

### 8.1. Compact live panel and expanded diagnostics

Place a compact **Live audio stats** panel below the LOCAL TEST label/timer and above the recording/call controls. Keep it outside the scrolling settings content. Show the actual route and recording tap, then separate **Received** and **Sent** rows with effective state, added audio delay in milliseconds, inference time in milliseconds per block, missed-block count, and small before/after level meters. An inactive direction says Off/Inactive rather than showing a previous measurement. Preserve a compact summary in the Call audio sheet header so tuning sliders does not hide the feedback. Reflow for landscape, large fonts and narrow displays; do not cover mute, hang-up or recording controls. Warnings use text/icons as well as color.

Tapping the panel opens an expandable Diagnostics view with latency breakdown, performance, levels and route information. Include Pause display (freezes the readout, not audio), Reset stats (measurement window only, not model/settings/recording), and Export report. A frozen view is visibly labeled and still receives critical mute/preemption/error status. Opening diagnostics never opens the microphone or starts a test.

### 8.2. Latency labels must describe different measurements

| Label | Required meaning |
|---|---|
| Added audio delay — Received / Sent | Configured source-alignment delay of the active local processing path, including model lookahead, conversion and fixed scheduling allowance. Show the target as Configured; show an observed hook-to-hook source offset separately only when matching source intervals prove it. Include any received-effect lookahead actually used. Comparison bypass displays retained delay; stable Off displays zero *additional processing* delay, not zero device latency. |
| Inference time | Measured worker wall time for processing one direction's block across its active channels. Show latest, mean, p95 and maximum, sample count and the five-second window. This is not model lookahead, audio delay, or a CPU-utilization percentage. Loading/reset time is reported separately. |
| Queue wait / deadline | Measured submission-to-worker-start wait plus callback/block duration, queue occupancy, missed eligible blocks and fallback counts. Do not add inference or queue time onto a fixed delay that already budgets for them and call that a measured total. |
| Input / output device delay | Backend timestamp-based estimate, only when the active backend exposes valid frame-position/time pairs in a verified timebase. Query off the audio callback. Label Estimated, identify the endpoint, and invalidate at route changes, restarts or clock discontinuities. Unsupported/stale estimates say Unavailable. Do not switch backend to obtain a nicer number. |
| Local round-trip test | Optional, explicitly started independent test playback plus microphone capture, with delay derived from a credible reference match. Label the actual taps, included processing and acoustic geometry; show successful matches and spread/confidence. Keep call echo processing unchanged, do not raise volume automatically, and never loop the live mic into the speaker. A suppressed/ambiguous reference produces No reliable measurement, not an invented delay. This is not a remote-call or network measurement. |

Do not sum Sent and Received delay unless an explicitly described test really traverses those stages in series. Both mode runs parallel streams. The mock has no network round-trip time, packet loss or remote-device latency; those fields say Not applicable, not zero. Measured, Estimated, Configured and Unavailable must remain distinct in the screen and report. No fixed Pixel latency values are supplied by this specification.

### 8.3. Useful detailed statistics

Show requested versus effective input/output route, handset/speakerphone segment, confirmation state, backend, source/processing/output sample rates, channel count, sample representation, block size and model choice. Include the platform-reported microphone mapping and visible capture-effect policy when observable; unexposed vendor processing stays Unknown.

Show before/after digital peak and RMS levels, per-channel clipping counts, user-marked speech/pause levels, model-estimated local SNR with its label, compressor/limiter gain reduction when the corresponding received stages expose it, and digital headroom. Statistics identify their tap and unit; digital dBFS is not acoustic sound-pressure level. Pause-level reduction is not automatically speech-quality improvement. Do not label float samples that have merely exceeded unity as confirmed physical clipping; distinguish peak overs, PCM saturation and limiter action.

Performance details include inference mean/p95/max, deadline misses with eligible-block denominator, dry fallback count/reason, queue fill/high-water mark, input/output underruns or overruns when reported by the backend, recording dropped blocks and writer backlog. App process CPU/RAM and platform thermal status may be sampled slowly where available; label their scope, and do not present battery temperature as processor temperature. Keep app/native/model revisions and whether each stream is actually audible in the report.

### 8.4. Handset/speakerphone and settings comparisons

Attach a numeric summary to each recorded route take and each completed A/B settings pass. Present Handset versus Speakerphone side by side with differences in levels, marked pause level, clipping, timing, inference and missed blocks where both measurements exist. Preserve route, tap, selected interval, settings revision, communication volume and fixed-position/typical-use procedure. Compare capture statistics before audition-only loudness matching. Do not subtract unrelated sample waveforms or claim a route-only improvement from two differently spoken takes. Do not silently pool measurements across a route, model, settings snapshot or recording-tap change; start a labeled segment and retain completed summaries.

### 8.5. Low-overhead sampling and privacy

Use fixed-size numeric snapshots. Refresh the compact panel at most ten times per second while the lab is visible; slower platform CPU/RAM/thermal sampling is at most once per second. Compute percentiles, strings, plots and exports away from audio callbacks; expensive process-memory queries run only while expanded diagnostics is visible. Bound history and label the measurement window, warm-up and stale values. Reset stats must not clear the denoiser's overload latch or conceal incomplete audio; keep session-total fault counts alongside resettable window counts. A pending previous-owner snapshot cannot reappear after preemption.

Tag counters at the actual production send/receive hooks, not just the lab driver. Reports include methods, units, validity, source-interval/generation evidence, fallback/missing-block markers and settings/model revisions, but no recordings unless separately selected, no contacts, phone numbers, account/call identifiers, private paths or device addresses. Extra lab instrumentation remains dormant during ordinary calls. Export a human-readable report and versioned numeric JSON for later comparison; never automatically upload either.

## 9. Acceptance and release gates

| Area | Required evidence |
|---|---|
| Shared path | Synthetic sentinel samples increment actual native hook counters. Received output reaches the existing reverse-reference/output path. Sent audition has no received denoiser/EQ contribution. Test both available backend configurations. |
| Physical microphone routes | Capture separate confirmed handset and speakerphone takes through the real backend. Record actual route/tap metadata and paired pre/post-denoiser tracks; changes invalidate old-route audio. Exercise fixed-position and typical-use procedures, and prove no extra recorder or live microphone-to-speaker feedback path is created. Device results may show little or no difference; do not invent a change. |
| Acoustic duplex | Received reference plays physically while microphone records; verify actual output/reference alignment, residual echo and speech during playback. Stop live capture before sent-result speaker audition. Simulate preemption, microphone privacy, route failure, writer overflow and mute at every tap, including pre-WebRTC input. Digital reference alone is not physical speaker proof. |
| Reproducibility | Same source, snapshot, reset, and pre-roll reproduce matching aligned samples within established floating-point tolerances. Loop/seek/pause/model edits never emit a previous generation. Test mono/stereo, all supported rates, silence, near-clipping input, and a short selection. |
| Settings | Lab edits and crashes leave ordinary preferences unchanged; Apply copies only selected validated sections coherently. A/B and live settings never cross directions or survive into an unrelated owner. |
| Privacy/preemption | Tests force real-call arrival during recording, playback, loading, and final output hand-off. No lab sample reaches the real-call sender and no real-call sample reaches the lab writer/export. Mute/unmute and route changes reject stale capture. Verify no call-history/contact writes or lab signaling. |
| Failures | Disk full, queue saturation, denied/revoked microphone permission, corrupt clips/models, rapid start/stop, focus loss, headset removal, and stale cleanup all stop the affected operation safely with clear status. Ordinary call startup remains available. |
| Interface/device | Verify real rendered call screen, route selection, controls, A/B, rotation/back/lock, permission indicators, and AMOLED on Android. Test Received, Sent, Both, both models, and available earpiece/speaker/headset routes on the Pixel; mark unavailable routes NOT TESTED. |
| Stats and timing | Verify compact/expanded visibility while tuning, defined taps/units and Configured/Measured/Estimated labels. Fake-clock tests reject stale/cross-owner data, clock resets and double-counted delay; Off versus aligned bypass remain distinct. Confirm known source offsets, percentile windows, queue/fault denominators, recording gaps and route-segment comparisons. Reset stats cannot reset overload or lose session totals. Missing backend timestamps/reference matches show unavailable; frame/callback allocation and diagnostic overhead are measured on-device. |
| Package | Optimized ARM64 build/lint, retained JNI, unchanged model hashes, native ABI/alignment, and same-certificate increasing-version update. Test/debug entry must not make the app debuggable or expose exported recording controls. |

Keep host, Android emulation, and physical-phone evidence separate. A UI-only screen, offline denoiser test, or passing source-anchor check is not sufficient proof of call-route playback. There is no mock-call APK in this design deliverable. Do not merge to main or the DeepFilterNet release branch automatically.

## 10. Source grounding and handoff

All source references below are relative to `mekromn/mollyim-android` at baseline `3935ddf30aecd90f19a414095a9b78a549d1a146`:

- `docs/deepfilternet-release-171906.md`: supplied APK identity and verification boundaries. Expected certificate SHA-256: `eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`.
- `native/call-denoise/{transport.h,webrtc_adapter.h,service.h}` and `native/call-denoise/java/CallDenoiseGate.java`: current owner-bound send/receive processing and global control/factory lifecycle.
- `tools/deepfilter/patch_ringrtc.py`: pinned native hook ordering, capture stamps, and stop/mute connections.
- `app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/{CallDenoiseController.kt,SignalAudioManager.kt}`: current singleton settings and communication-routing policy.
- `app/src/main/java/org/thoughtcrime/securesms/components/webrtc/v2/{CallScreen.kt,CallAudioControls.kt}`: shared call presentation, real-recipient dependency, and current singleton-bound panel.

Platform references informing the route/capture distinction (requirements remain subject to actual backend/device verification):

- Android `AudioManager.setCommunicationDevice`: selects a communication output and the platform selects the matching input; unavailable routes can be rejected. https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)
- Android `AudioRecord`: active microphone/channel mapping, active recording configuration and actual routed device are queryable where supported. A preferred device is not proof of the actual input. https://developer.android.com/reference/android/media/AudioRecord
- AOSP capture preprocessing: product-specific audio policy maps sources to device, gain and preprocessing. This does not establish which internals change on this Pixel when its route changes. https://source.android.com/docs/core/audio/implement-pre-processing

- Oboe stream timestamp/latency contract: estimates are endpoint-specific, may be unsupported, and do not include unknown external-device delays. https://google.github.io/oboe/classoboe_1_1_audio_stream.html
- Android audio latency measurement boundaries: https://developer.android.com/ndk/guides/audio/audio-latency

**Next:** owner review of this written specification, then a concrete implementation plan identifying native test-endpoint construction, session-scoped controls, recording queues, source/sink interception, and preemption tests. Existing DeepFilterNet implementation is the baseline, not work to repeat.