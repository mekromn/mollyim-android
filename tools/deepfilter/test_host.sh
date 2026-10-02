#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MODE=${1:-timeline}
WORK=${DEEPFILTER_HOST_OUT:-"$ROOT/out/denoise-host"}
mkdir -p "$WORK"
if [[ ! -f "$ROOT/native/call-denoise/timeline.h" ]]; then
  echo 'FAIL: fixed-delay rate adapter is not implemented' >&2; exit 1
fi
: "${WEBRTC_SOURCE:?Set WEBRTC_SOURCE to the pinned WebRTC source checkout}"
CXX=${CXX:-g++}
FLAGS=(-std=c++20 -O2 -g -pthread -Wall -Wextra -Werror)
if [[ ${SANITIZER:-address} == thread ]]; then FLAGS+=(-fsanitize=thread); else FLAGS+=(-fsanitize=address,undefined -fno-omit-frame-pointer); fi
EXTRA=()
if [[ "$MODE" == worker || "$MODE" == library || "$MODE" == transport ]]; then
  EXTRA+=("$ROOT/native/call-denoise/control.cc" "$ROOT/native/call-denoise/worker.cc" "$ROOT/native/call-denoise/library.cc")
fi
"$CXX" "${FLAGS[@]}" -DWEBRTC_POSIX -DWEBRTC_LINUX \
  -I "$ROOT/native/deepfilter-runtime/include" -I "$ROOT/native/call-denoise/tests/support" -I "$ROOT/native/call-denoise" -I "$WEBRTC_SOURCE" \
  "${EXTRA[@]}" "$ROOT/native/call-denoise/tests/${MODE}_test.cc" "$ROOT/native/call-denoise/timeline.cc" \
  "$ROOT/native/call-denoise/tests/support/platform.cc" \
  "$WEBRTC_SOURCE/common_audio/resampler/push_sinc_resampler.cc" \
  "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler.cc" \
  "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler_sse.cc" \
  "$WEBRTC_SOURCE/common_audio/resampler/sinc_resampler_avx2.cc" \
  "$WEBRTC_SOURCE/common_audio/audio_util.cc" \
  "$WEBRTC_SOURCE/rtc_base/memory/aligned_malloc.cc" -mavx2 -mfma -ldl -o "$WORK/$MODE"
"$WORK/$MODE"
