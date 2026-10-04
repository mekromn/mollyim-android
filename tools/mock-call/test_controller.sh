#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio"
OUT="$ROOT/out/mock-controller-contracts"
mkdir -p "$OUT"
COROUTINES="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlinx-coroutines-core-jvm.jar"
python3 - "$A" "$OUT" <<'PY'
from pathlib import Path
import sys
src,out=map(Path,sys.argv[1:])
(out/'Context.kt').write_text('package android.content\nopen class Context\n')
s=(src/'CallAudioSession.kt').read_text();s=s[:s.index('/** Ordinary calls')];(out/'CallAudioSession.kt').write_text(s)
s=(src/'CallDenoiseController.kt').read_text();s='package org.thoughtcrime.securesms.webrtc.audio\n'+s[s.index('data class DenoiseStatus('):];(out/'DenoiseStatus.kt').write_text(s)
PY
kotlinc "$OUT/Context.kt" "$OUT/CallAudioSession.kt" "$OUT/DenoiseStatus.kt" \
 "$A/CallDenoiseSettings.kt" "$A/IncomingAudioSettings.kt" "$A/DenoiseCoordinator.kt" \
 "$A/mock/LabConfiguration.kt" "$A/mock/LabCoordinator.kt" "$A/mock/LabLifecycle.kt" \
 "$A/mock/LabRouteController.kt" "$A/mock/LabWaveCodec.kt" "$A/mock/LabTakeStore.kt" \
 "$A/mock/LabStatsSnapshot.kt" "$A/mock/LabBenchmark.kt" "$A/mock/LabControllerPorts.kt" "$A/mock/MockCallLabController.kt" \
 "$ROOT/tools/mock-call/tests/ControllerContracts.kt" -cp "$COROUTINES" -include-runtime -d "$OUT/tests.jar"
java -cp "$OUT/tests.jar:$COROUTINES" org.thoughtcrime.securesms.webrtc.audio.mock.ControllerContractsKt
