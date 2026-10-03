#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/mock"
test -f "$A/LabStatsSnapshot.kt" || { echo 'FAIL: missing local diagnostics'; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/LabStatsSnapshot.kt" "$ROOT/tools/mock-call/tests/StatsContracts.kt" -include-runtime -d "$WORK/stats.jar"
java -jar "$WORK/stats.jar"
