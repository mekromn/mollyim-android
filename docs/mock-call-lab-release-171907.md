# Mock Call Lab 171907 — signed core test APK

Delivery checkpoint: 2026-10-03. This supersedes earlier incomplete-build notes. The core lab has a signed test APK; the entire v3 specification is NOT complete and production readiness is NOT claimed. Continue from device feedback and the explicit missing features below, not another design/reimplementation round.

## Signed update identity

- File supplied directly in chat: `Molly-Audio-Mock-Call-Lab-arm64-release.apk`.
- Size: **124,054,318 bytes** (124.1 MB decimal).
- SHA-256: `bace96ddbf3c91d15923254c86b76ba7c79ba38a410979151a37a2a4a7d2d3b3`.
- Package `com.mekromn.mollyaudio`, version code **171907**, version name `8.19.2-4`.
- ARM64-only, minSdk 27, targetSdk 35, non-debuggable. Lab route confirmation currently requires **Android 12+**; ordinary app minimum is unchanged.
- Original signing certificate SHA-256: `eb6825c9abab77a52cf12444d078d4b808c4c708ec31efaf8c59b0adf686557b`.
- APK v2/v3 signatures and same-package/same-certificate/increased-version identity verified against delivered DeepFilterNet 171906. All **4075 ZIP members** are unchanged by signing. No replacement key or private credentials in GitHub.

Install directly over existing Molly Audio. Do not uninstall, clear data or repeat account registration. No account operation or registration/database-schema code change was part of this work. Physical installation and session retention still need phone confirmation.

## Using the core lab

Open **Settings → Help → Debug → Mock Call Lab**. The Debug dialog contains the lab entry; no unrelated internal flags are required. The activity is passphrase-protected and non-exported.

Choose **Audio route → Normal call — Earpiece** or **Speakerphone**, confirm the actual route label, then explicitly **Record microphone**. Use **Stop / pause** and **Takes / export → Save** to keep a take. Record a separate take in the other route. Compare through the same output; the Fixed position/Typical use labels distinguish the procedure.

For repeatable denoiser tuning choose **Sent → Sent replay source → Before sent DeepFilterNet**, then **Play**, **Loop** and **Call audio**. Received replay uses the received denoiser and original received EQ/compressor/gain/limiter. Sent audition does not cascade through received effects. A/B settings snapshots and whole-path comparison are available. Lab settings are separate from ordinary preferences; only **Apply selected settings to calls** publishes them.

For **Speakerphone echo test**, first select a received clip and Speakerphone. The independent clip plays while the real microphone records; live sent audio never feeds into the speaker. Stop capture before listening to its recorded result.

Source/tap recordings remain private in no-backup storage until explicit export. Unsaved takes are discarded on exit/preemption. Duration limit is 120 seconds and aggregate private storage limit 256 MiB. Real calls have priority; host tests cover revocation, stale owners, tap gaps and hardware-only handoff. Actual phone preemption is not yet tested.

The native endpoint uses the actual factory's WebRTC AudioState/ADM/mixer/capture/send/receive implementation and selected Java/Oboe backend, with local source/sender and isolated controls. It creates no network PeerConnection/call or contact/history item. Native compilation/source tests are not proof of physical route behavior.

## Live statistics

Pinned stats remain visible on the lab screen and while tuning: separate Sent/Received effective states, configured added DSP delay, measured inference mean/p95, rolling missed blocks and before/after peak meters. Expanded diagnostics contains observable format/backend/route data, recording faults, tap RMS/peak/saturation readings and text/JSON exports.

Configured processing delay is not measured device or acoustic round-trip latency. Worker inference time is also not that delay. Do not sum those quantities or parallel directions. No network RTT exists in the mock. Device-delay estimates are currently unavailable.

## Verification evidence

Compiled app source: `ef56e0b7967c8cb7515717b3bc43760b88808813`.
Verification correction source: `ff0b4be322bd12f07d4674bc317fc0959893f529`.
Native build source: `f735bc931655e062408e1530dc78fc0a5df6ae76`.

App run **37099403894** completed release lint and optimized assembly successfully. Its later original verifier failed because SDK36 renders the explicitly false exported attribute differently. The actual compiled manifest and a new regression reproduced that checker error; the corrected full verifier passed without changing product bytes. The original overall run is not mislabeled successful.

Final package proof **37101259221** passed every stage: exact compiled input/provenance, full APK manifest/model/native/JNI checks, alignment and actual packaged ARM64 model execution with AOSP Bionic under QEMU user mode. Standard and Low Latency each processed 48,000 samples; maximum reference errors were **2.037268132e-09** and **1.490116119e-08**, with reset/channel-isolation checks passing. This is native inference, NOT Android UI, physical routing, acoustic measurement or a live-call test.

Unsigned APK SHA-256: `ba488dd2ae1769c03e53086963fb3476b3a4fb377f34467986d01db4dc634a38`.
Final proof report artifact **11266581288**, ZIP SHA-256 `d39de5f28db2f2b27bf1693a2d1be1fde4131d31e4abd740974f7be147669bbf`.
Verified unsigned artifact **11266621208** is also available in that proof run.

Signed payload verification also confirms the old incoming-effects JNI/native ABI survives, both model archives match 171906, and DeepFilterNet executable code is unchanged. Native inputs match the verified AAR. Existing model archive finalization restores pinned gzip assets without changing compiled code.

Fresh application host contracts, scope ASan/UBSan and separate TSan checks, endpoint/API fixtures, existing DSP and AMOLED checks passed. One aggregate LOCAL command stopped on a missing file in a partial source export; full pinned-source CI **37099403893** passed the corresponding patch/header checks. Preserve that distinction. Full-project all-tests success is not claimed.

## Known unfinished v3 items and device boundaries

- Device timestamp estimates, acoustic round-trip measurement, CPU/RAM/thermal display and complete queue/latest/max timing breakdowns.
- Automatic numerical side-by-side route summaries; manual route-labeled takes and fixed-output audition exist.
- Loudness matching, live headset microphone monitoring, persistent lab preset/configuration export. A/B snapshots are session-local.
- Complete native stats-window reset; current reset does not reset overload protection or discard session faults.
- 44.1-kHz playback conversion. Such original WAVs can be preserved, but replay currently needs 8/16/32/48 kHz mono/stereo PCM16 or float32. Working float-to-PCM conversion at an existing call boundary is labeled and does not overwrite originals.

Physical installation, actual handset/speakerphone microphones, rendered Compose UI, incoming-call preemption, Bluetooth/USB, sustained load and acoustic behavior are **NOT RUN**. This was an author self-review, not an independent review. No merge to main or the DeepFilterNet release branch was performed.
