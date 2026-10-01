#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
INPUTS=${DEEPFILTER_TEST_INPUTS:-"$ROOT/out/deepfilter-inputs/fixtures"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CP="$INPUTS/junit-4.13.2.jar:$INPUTS/hamcrest-core-1.3.jar"
for jar in "$INPUTS/junit-4.13.2.jar" "$INPUTS/hamcrest-core-1.3.jar"; do test -f "$jar"; done
kotlinc -cp "$CP" "$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/CallDenoiseSettings.kt" "$ROOT/app/src/test/java/org/thoughtcrime/securesms/webrtc/audio/CallDenoiseSettingsTest.kt" -include-runtime -d "$WORK/settings.jar"
java -cp "$WORK/settings.jar:$CP" org.junit.runner.JUnitCore org.thoughtcrime.securesms.webrtc.audio.CallDenoiseSettingsTest
