# DeepFilterNet call integration checkpoint

Updated 2026-10-02. Development checkpoint, **not an APK release**.
The owner approved the specification, plan and execution. Continue without
another approval round. Preserve the installed AMOLED build and account.

## Completed source work

Tasks 1–2: independent settings, pinned model provenance, checked Rust runtime,
both real-model reference tests and ARM64 cross-compilation. Runtime source
`0434920` produced host run `36929152545` and Android run `36929152476`.
Cross-compilation/ELF checks are not Android execution or phone-call tests.

Task 3: fixed-capacity packets, SPSC queues, matched original history, source-
interval metadata and real WebRTC rate conversion. Commit `9049551` passed
adapter CI `36977172413`, including all 16 model/rate/channel combinations.

The pinned push converter's integer priming makes native round-trip delay
19/22/27 samples at 8/16/32 kHz, not just its half-kernel estimate. Exact padding
keeps the planned total budgets: Standard 50 ms at 48 kHz / 60 ms below; Low
Latency 30 ms / 40 ms. These include the 20 ms worker allowance, not network
latency or measured Pixel performance. Attenuation zero is upstream immediate
copy, so our worker treats it as aligned dry bypass instead of delayed wet audio.

## Task 4: independent workers

Implemented an event-driven worker per constructed direction. Dormant workers
sleep while Off. Callbacks do not create threads, load models, run inference,
or wait for the settings writer mutex. Fixed queues keep matched dry fallback;
Stable Off returns Direct without denoising resampling or submitted audio.
Comparison bypass and zero attenuation suspend inference but keep alignment.

Model changes release old contexts before replacement. Internal request epochs
reject stale model completions without relabeling external owner/generation
stamps. Scalar parameter edits use coherent atomic-word snapshots without
resetting the stream. A failed cache is evicted so explicit Retry can use repaired
assets. Three consecutive eligible misses or 26 misses in a full 500-callback
window latch only that direction. Unsupported formats cannot reset that latch.
Turning Off uses a bounded two-block transition to Direct.

`DirectionProcessor::Invalidate()` cancels in-flight work and requests release
without needing another audio callback. This is a primitive for Task 5, **not a
completed call-mute connection**. Model loading, reset, joining and destruction
remain off callbacks. Fatal native stalls, aborts and OOM are not recoverable
bypass promises. External owners still must keep control/factory objects alive
until workers have stopped.

## Fresh verification for Task 4

- 19 worker/control regressions pass under ASan/UBSan and separately TSan:
  actual threaded load cancellation, concurrent coherent edits, 100,000 callback
  iterations checked for allocation, failure isolation, model changes, matched
  fallback, zero attenuation, and overload persistence.
- Both real models load through the new dynamic runtime factory. Repeated reset,
  silent-channel isolation and real-worker output match independent upstream
  vectors. Host maximum error: Standard 0; Low Latency 1.49012e-08. The worker
  reference harness is deterministic, not a phone performance benchmark.
- Seven existing Python provenance/build/lock tests pass. Existing ten DSP tests,
  sanitizer, three patch fixtures, strict integration-header fixture and standalone
  incoming settings checks pass. Six AMOLED theme tests and the existing timeline
  adapter tests pass. Production worker files compile with no exceptions or RTTI.
- Full Android Gradle and Kotlin JUnit settings tests were not run locally: the
  SDK/JUnit fixture set is absent. The required host-runtime fixture path was
  restored to the verified artifact before rerunning the Python suite. CI provides
  Android/Gradle checks separately; they are not implied by host tests.

The loaded Rust library is a verified release artifact, not sanitizer-
instrumented. Worker/adapter test code is instrumented. No physical phone,
live call, new call-screen UI, or complete DeepFilterNet APK is verified here.

## Resume next — Task 5, not Task 1

Read `docs/superpowers/plans/2026-10-01-deepfilternet-calls.md` and the approved
specification. Continue authoritative owner/generation gates for one-to-one,
group and remote mute, both call hooks and lifecycle handling. Then finish
assets/controller, two-tab UI, patched RingRTC compilation and signed update.
Do not merge automatically or recreate completed model/adapter implementations.

Keep package `com.mekromn.mollyaudio`, AMOLED and received EQ settings. Next APK
version is 171906. Reuse signing certificate
`eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`.
Never generate a replacement key, clear data, modify registration, or label the
installed 171905 APK a DeepFilterNet build.

## Task 5 source checkpoint (native integration build still pending)

Added checked factory-to-transport binding, per-call owner and separate sent /
received gate generations, pre-APM capture stamps carried through AudioFrame,
nonblocking final hand-off leases, and source patches for direct/group/remote
mute, end/dispose, and native capture stop. A stale factory cannot adopt a new
call, including reuse of the shared group factory. The receive hook now feeds
float denoiser output directly into the existing float effects before final PCM
conversion. Unsupported geometry retains ordinary audio with a guarded capture
stamp rather than being mistaken for a valid model frame.

Thirteen gate/transport tests and five factory-ownership tests pass under
ASan/UBSan and separately TSan. Strict Chromium-style header/source compilation
with no exceptions/RTTI also passes after replacing raw snapshot memcpy and
iterator arithmetic with bounded array/span operations. These source fixtures
are NOT Android execution. Exact pinned patch application is idempotent and
rejects version drift. Actual RingRTC native compilation is the next gate.

Rulings: final hand-off and mute need a linearization point; the audio callback
uses try-lock only, while control can wait for an already accepted short sender
hand-off, never for inference. AudioFrame uses four provenance fields rather
than three so format changes remain callback-local without aliasing mute epochs.
A verified factory-construction handshake is required before binding; a missing
or ambiguous binding preserves unmodified calling, not a misattributed session.

Task 6 controller/assets and Task 7 two-tab UI still require implementation.
No DeepFilterNet APK or phone-call result is claimed by this source checkpoint.

## Tasks 6 and 7 source checkpoint

Added independent persistent settings/controller, verified atomic installation of
bundled models to private storage, optional retained JNI bridge, and actual
physical-route invalidation. Background initialization uses the existing
RingRTC initialization hook through IncomingAudioController. Both new filters
remain Off by default; editing a disabled filter never loads models or audio.
A late asset completion reconciles current settings and cannot restore an old
enable request. Storage/native failures leave ordinary calling available.

Added Call audio with independent Received and Sent tabs, all approved model /
suppression / post-filter / threshold / preset controls, bypass, Retry, reset,
small numeric diagnostics and independent sheet visibility. Existing received
effects are reused without changing their preference format or processing.
AMOLED styling and existing mute/hang-up controls remain.

Ten executed pure-Kotlin controller/asset contracts, Java JNI descriptor/missing-
engine checks and Android-facing Kotlin API-fixture compilation pass. Two UI
source-contract checks and six AMOLED host regressions pass. New JUnit and
Compose instrumentation test sources are included; full Android compilation,
instrumentation rendering and actual device calls are separate gates, not
claimed by source checks. The app-compilation workflow deliberately emits no APK.

The final native update must additionally reset manual comparison bypass on a
new call and expose that state through status field 14. These follow-up native
changes are tested locally but not part of the first Task 5 native run. Never
package the earlier AAR without applying and verifying that follow-up.
