#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/mock"
test -f "$A/LabWaveCodec.kt" || { echo 'FAIL: missing WAV recording implementation'; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/LabWaveCodec.kt" "$ROOT/tools/mock-call/tests/WaveContracts.kt" -include-runtime -d "$WORK/wave.jar"
java -jar "$WORK/wave.jar"
