#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/mock"
test -f "$A/LabLifecycle.kt" || { echo 'FAIL: missing hardware lifecycle'; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/LabLifecycle.kt" "$ROOT/tools/mock-call/tests/LifetimeContracts.kt" -include-runtime -d "$WORK/lifetime.jar"
java -jar "$WORK/lifetime.jar"
