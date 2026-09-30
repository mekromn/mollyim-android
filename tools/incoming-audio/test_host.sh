#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CXX=${CXX:-clang++}
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread "$ROOT/native/incoming-audio/dsp_test.cc" -o "$WORK/dsp-test"
"$WORK/dsp-test"
"$CXX" -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -pthread "$ROOT/native/incoming-audio/dsp_test.cc" -o "$WORK/dsp-sanitized"
"$WORK/dsp-sanitized"
python3 "$ROOT/tools/incoming-audio/test_patch.py"
if command -v kotlinc >/dev/null; then
  kotlinc "$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/IncomingAudioSettings.kt" "$ROOT/native/incoming-audio/settings_test.kt" -include-runtime -d "$WORK/settings-test.jar"
  java -jar "$WORK/settings-test.jar"
else
  echo 'Kotlin compiler not present; standalone settings checks not run.'
fi
