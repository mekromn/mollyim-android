#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/mock"
test -f "$A/LabTakeStore.kt" || { echo 'FAIL: missing bounded take store'; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/LabWaveCodec.kt" "$A/LabTakeStore.kt" "$ROOT/tools/mock-call/tests/StoreContracts.kt" -include-runtime -d "$WORK/store.jar"
java -jar "$WORK/store.jar"
