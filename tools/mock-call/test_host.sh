#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MODE=${1:-scope}
if [[ "$MODE" == scope ]];then exec bash "$ROOT/tools/mock-call/test_scope.sh";fi
WORK=${MOCK_HOST_OUT:-"$ROOT/out/mock-host"};mkdir -p "$WORK/include/audio"
for mapping in 'incoming-audio molly_incoming' 'call-denoise molly_denoise' 'mock-call molly_mock';do read -r folder target <<< "$mapping";ln -sfn "$ROOT/native/$folder" "$WORK/include/audio/$target";done
: "${WEBRTC_SOURCE:?Pinned WebRTC sources required}"
CXX=${CXX:-g++};FLAGS=(-std=c++20 -O1 -g -pthread -Wall -Wextra -Werror)
if [[ ${SANITIZER:-address} == thread ]];then FLAGS+=(-fsanitize=thread -fno-pie -no-pie);else FLAGS+=(-fsanitize=address,undefined -fno-omit-frame-pointer);fi
SOURCES=("$ROOT/native/mock-call/scope.cc")
for n in control worker library timeline gate transport service;do SOURCES+=("$ROOT/native/call-denoise/$n.cc");done
"$CXX" "${FLAGS[@]}" -DWEBRTC_POSIX -DWEBRTC_LINUX -I "$WORK/include" -I "$ROOT/native/mock-call" -I "$ROOT/native/deepfilter-runtime/include" -I "$ROOT/native/call-denoise/tests/support" -I "$WEBRTC_SOURCE" "${SOURCES[@]}" "$ROOT/native/mock-call/tests/${MODE}_test.cc" \
 "$ROOT/native/call-denoise/tests/support/platform.cc" "$WEBRTC_SOURCE/common_audio/resampler/push_sinc_resampler.cc" "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler.cc" "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler_sse.cc" "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler_avx2.cc" "$WEBRTC_SOURCE/common_audio/audio_util.cc" "$WEBRTC_SOURCE/rtc_base/memory/aligned_malloc.cc" -mavx2 -mfma -ldl -o "$WORK/$MODE"
"$WORK/$MODE"
