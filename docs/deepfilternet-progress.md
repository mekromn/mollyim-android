# DeepFilterNet call integration checkpoint

Updated 2026-10-02. This is a development checkpoint, **not an APK release**.
The owner approved the specification, implementation plan, and execution in this
chat. Continue that plan without repeating approval or rebuilding finished tasks.

## Implemented before this checkpoint

Tasks 1 and 2 have real code on `feature/deepfilternet-calls`: independent settings,
checked model provenance, fallible Rust inference, both actual model reference
tests, and Android ARM64 cross-compilation. Source checkpoint `0434920` produced
host runtime run `36929152545` and Android run `36929152476`. The latter establishes
compilation and ELF checks, not on-device inference or a live call.

## Task 3: bounded, source-timed audio adapters

Implemented fixed-capacity input/output packet types, single-producer/single-
consumer queues, matched original-audio history, source-interval/timestamp
calculation, worker-owned real WebRTC converters, and fixed alignment padding.
No live call hooks are connected yet. A partial stereo failure clears the result
and poisons the adapter until replacement; changed owner/generation or missing
input cannot resume an old partially advanced stream.

The new tests first failed with the adapter missing. Additional tests reproduced
an incorrect converter-delay assumption, invalid-plan division by zero, and
partially filled stereo output on an engine failure. Those cases now pass.

### Timing decision recorded during implementation

The plan's half-kernel formula is an estimate, not the complete delay of the
pinned push converter's integer priming. `PushSincResampler` discards a truncated
`ChunkSize()` on first use. Measured round-trip native-rate delays are **19, 22,
and 27 samples** at 8, 16, and 32 kHz. Use those integer delays, followed by exact
integer padding. This removes the observed phase offset without adding another
interpolation filter. The planned total 10 ms block budgets remain unchanged:
Standard 50 ms at 48 kHz / 60 ms below; Low Latency 30 ms / 40 ms. These are adapter
budgets including a future 20 ms worker allowance, not network-call latency or
measured Pixel performance. The scheduling worker itself is Task 4.

The unmodified upstream runtime immediately copies input at attenuation zero;
it does not preserve normal model lookahead then. Task 4 must treat zero as
aligned dry bypass rather than run it as a delayed wet result.

## Verification boundaries

Fresh host ASan/UBSan and ThreadSanitizer queue/timing tests pass, including
100,000 concurrently transferred packets and sequence wrap. Tests compile the
actual pinned WebRTC resampler sources (`63074e7`); host-only compatibility
headers replace logging/check plumbing and CPU dispatch, not the converters.

Both actual DeepFilterNet3 models match the independent upstream reference
vectors within 1e-5 maximum absolute sample error. Real-model delay tests cover
all 16 combinations of 8/16/32/48 kHz, mono/stereo and Standard/Low Latency,
with zero-sample peak-position residual and a silent second channel. Delay
calibration deliberately keeps the processing stages transparent and supplies
a small pedestal on the active channel to avoid the upstream low-RMS early
return. Ordinary noisy-speech denoising is checked separately by the reference
vectors; the calibration is not presented as a quality benchmark.

The downloaded host runtime is not sanitizer-instrumented; the adapter and
converter test code are. None of these tests establish phone-call behavior,
microphone privacy gates, Bluetooth routing, or Android UI rendering.

## Resume next

Task 4: independent workers, coherent controls, aligned fallback, overload latch,
state/diagnostic snapshots and bounded teardown. Then Task 5 authoritative mute
and call hooks, Task 6 persistent controller/assets, Task 7 two-tab UI, Task 8
actual optimized APK and same-key signing. Task 9 physical calls remains a
separate, explicitly reported acceptance step.

Preserve `com.mekromn.mollyaudio`, AMOLED, received EQ settings, original signing
certificate, and logged-in account. Next APK version is 171906. Never generate a
replacement signing key, clear app data, modify registration, or call the current
171905 APK a DeepFilterNet build.
