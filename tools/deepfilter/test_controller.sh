#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio"
for file in DenoiseCoordinator.kt ModelAssetStore.kt; do
  test -f "$A/$file" || { echo "FAIL: $file is not implemented" >&2; exit 1; }
done
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
kotlinc "$A/CallDenoiseSettings.kt" "$A/DenoiseCoordinator.kt" "$A/ModelAssetStore.kt" "$ROOT/tools/deepfilter/controller-tests/ControllerContracts.kt" -include-runtime -d "$WORK/tests.jar"
java -jar "$WORK/tests.jar"
