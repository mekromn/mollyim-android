#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio"
test -f "$A/mock/LabRouteController.kt" || { echo 'FAIL: missing route-state implementation'; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/CallDenoiseSettings.kt" "$A/IncomingAudioSettings.kt" "$A/mock/LabConfiguration.kt" "$A/mock/LabRouteController.kt" "$ROOT/tools/mock-call/tests/RouteContracts.kt" -include-runtime -d "$WORK/routes.jar"
java -jar "$WORK/routes.jar"
