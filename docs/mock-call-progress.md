# Mock Call Lab implementation checkpoint

Owner approved specification v3 and execution of the implementation plan. No
additional design approval is needed. Branch: feature/mock-call-lab. Preserve
installed Molly Audio 171906, account, package and original signing certificate.

## Source implemented; actual native/Android builds are separate gates

Isolated Kotlin configuration/coordinator and native scope now keep lab edits
outside ordinary call preferences and native effect state. Paired denoiser
settings use a coherent snapshot; received effects have separate controls/meters.

The local endpoint is connected at the production factory's initialized voice
engine, uses its actual AudioTransport/mixer/APM/Java-or-Oboe ADM, and creates no
PeerConnection. Clip-only playback never starts recording. Sent clip replay
uses the same factored sent stage as SendProcessedData. Received and monitor
selection precede the real reverse-stream reference. Optional observers are
null for production streams. AudioFrame carries the local generation through
existing asynchronous capture processing.

There are fixed recording queues, source/segment metadata, pre/post-sent float
taps, earliest backend PCM capture and actual digital playback-reference taps.
The local factory has a separate owner, controls and lifetime. Production factory
creation and group/direct session binding revoke the local endpoint and await
only hardware stop, with a two-second bound. They do not wait for model disposal.

Host scope/packet/state tests, endpoint API-fixture execution and strict native
source compilation pass. A deterministic test reproduced revocation waiting
behind a blocked hardware-stop command; revocation now closes atomic gates
without waiting for that command lock. Actual pinned patch application is tested
for ordering, lifetime and idempotence. API fixtures are NOT proof of Android
hardware behavior or a full native integration build.

## Remaining before APK delivery

Build and verify the actual patched native AAR; finish the Android lab session,
real route/recording lifecycle, WAV import/export, UI reuse and statistics.
Validate real-model endpoint output, full Android compilation, release lint,
JNI/model/native payload and original signing identity. Physical device routing,
recording, acoustic behavior and UI acceptance remain explicitly NOT RUN.

The current endpoint refuses live sent monitoring, rather than permitting an
unverified microphone-to-speaker route. Record-then-play and independent receive
speaker playback with live capture are supported by its contract. Any future
headset monitor must have an authoritative route-loss guard first.

Never label the installed DeepFilterNet APK as a Mock Call Lab build. Version
171907 is reserved for the eventual verified signed update, not yet produced.
