#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${MOCK_HOST_OUT:-"$ROOT/out/mock-host"};mkdir -p "$WORK"
A="$ROOT/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio"
for file in "$ROOT/native/mock-call/scope.h" "$A/mock/LabConfiguration.kt" "$A/mock/LabCoordinator.kt"; do test -f "$file" || { echo "FAIL missing isolation implementation: $file" >&2;exit 1;};done
mkdir -p "$WORK/include/audio"
ln -sfn "$ROOT/native/incoming-audio" "$WORK/include/audio/molly_incoming"
ln -sfn "$ROOT/native/call-denoise" "$WORK/include/audio/molly_denoise"
SAN=(-fsanitize=address,undefined -fno-omit-frame-pointer)
if [[ ${SANITIZER:-address} == thread ]];then SAN=(-fsanitize=thread -fno-pie -no-pie);fi
${CXX:-g++} -std=c++20 -O1 -g -pthread -Wall -Wextra -Werror "${SAN[@]}" -I "$ROOT/native/mock-call" -I "$WORK/include" -I "$ROOT/native/deepfilter-runtime/include" "$ROOT/native/mock-call/tests/scope_test.cc" "$ROOT/native/mock-call/scope.cc" "$ROOT/native/call-denoise/control.cc" -o "$WORK/scope"
"$WORK/scope"
kotlinc "$A/IncomingAudioSettings.kt" "$A/CallDenoiseSettings.kt" "$A/mock/LabConfiguration.kt" "$A/mock/LabCoordinator.kt" "$ROOT/tools/mock-call/tests/ScopeContracts.kt" -include-runtime -d "$WORK/scope.jar"
java -jar "$WORK/scope.jar"
