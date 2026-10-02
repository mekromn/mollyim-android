# Molly Audio — Mock Call Lab design

**Date:** 2026-10-02  
**Status:** Revised specification for owner review, including the requested real microphone/speakerphone capture and handset-versus-speakerphone comparison. The in-chat design was approved; this document does not claim implementation or a new APK.  
**Repository / branch:** `mekromn/mollyim-android` / `feature/mock-call-lab`.  
**Source baseline:** `3935ddf30aecd90f19a414095a9b78a549d1a146`, the delivered DeepFilterNet release checkpoint.  
**Installed baseline:** Molly Audio 171906. Preserve `com.mekromn.mollyaudio`, ARM64, minSdk 27, targetSdk 35, optimized release packaging, and the original signing certificate. Reserve 171907 for the next update; recheck the installed/latest supplied version before signing.

## 1. Purpose and success criteria

Record a sample once, replay or loop it through the real call-processing paths, adjust the existing call-audio controls while listening, and deliberately apply the preferred settings to ordinary calls. Testing needs no second phone or connected participant. The lab must exercise actual native processing and communication-device routing, not just imitate the call screen around a separate media player.

The approved scope includes Received, Sent, and Both modes; physical microphone recording in handset and speakerphone routes, simultaneous communication-output playback and microphone capture, route-to-route recording comparison, and lossless sample import; playback, loop selection, original/processed comparison, two settings snapshots A/B, explicit save/export, numeric diagnostics, and temporary settings. It preserves DeepFilterNet Standard/Low Latency, received EQ/compressor/gain/limiter, AMOLED, and the signed-in account.

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

## 8. Diagnostics and proof of the exercised path

Display a numeric, session-scoped report: app/native/model revision, source kind, source format, actual processing/output rates and channels, backend, actual input/output route classes, handset/speakerphone segment and confirmation state, recording tap, platform-reported microphone mapping when available, visible capture-effect policy, communication volume, comparison procedure, enabled stages, effective denoiser states, model/adapter delay, mean/p95 processing time, deadline misses, fallback counts, dropped-recording blocks, input/output peaks, and clipping counts at defined taps. Invalid or stale values display unavailable.

Tag counters at the actual production send and receive hooks, not only in the lab driver. Record source-interval/generation counters sufficient to prove that a clip traversed the requested path. Counters and hashes must not contain contacts, phone numbers, call identifiers, paths exposing private filenames, Bluetooth device addresses, or audio. Poll small snapshots at the existing low UI rate. Instrumentation must be dormant during ordinary calls when the lab is closed.

Report pipeline delay separately from measured end-to-end local I/O timing; absent a measurement, label I/O timing unmeasured. Reports support manual bug review and do not assert an objective best denoiser from model-estimated SNR alone.

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

**Next:** owner review of this written specification, then a concrete implementation plan identifying native test-endpoint construction, session-scoped controls, recording queues, source/sink interception, and preemption tests. Existing DeepFilterNet implementation is the baseline, not work to repeat.
