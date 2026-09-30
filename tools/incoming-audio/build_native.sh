#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${INCOMING_AUDIO_WORK:-"${RUNNER_TEMP:-/tmp}/molly-incoming-audio"}
mkdir -p "$WORK" "$ROOT/out/incoming-audio"
for tool in git python3 rustup java protoc; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
test -d "$ANDROID_NDK_HOME" || { echo "Install NDK 28.0.13004108 first" >&2; exit 1; }
# gclient cherry-picks Molly's patches in a separate WebRTC checkout.
# Git needs commit metadata there even though this does not publish any commit.
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-Molly native builder}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-builder@localhost}"
export PYTHONUNBUFFERED=1
if [[ ! -d "$WORK/depot_tools/.git" ]]; then
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$WORK/depot_tools"
fi
export PATH="$WORK/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
if [[ ! -d "$WORK/ringrtc/.git" ]]; then
  git clone --depth 1 --branch v2.69.5-1 https://github.com/mollyim/ringrtc.git "$WORK/ringrtc"
fi
cd "$WORK/ringrtc"
[[ $(git rev-parse refs/tags/v2.69.5-1) == e05d1475a96806acad749da8030ea4b73cc5fdb4 ]] || { echo 'Pinned RingRTC tag changed; refusing to build' >&2; exit 1; }
rustup show
rustup target add aarch64-linux-android
./bin/prepare-workspace android
python3 "$ROOT/tools/incoming-audio/patch_ringrtc.py" "$WORK/ringrtc"
./bin/build-aar --release-build --arch arm64 -j "${BUILD_JOBS:-2}" --extra-ninja-flags="-j${BUILD_JOBS:-2}"
mapfile -t AARS < <(find "$WORK/ringrtc/out/release" -maxdepth 1 -type f -name '*.aar')
[[ ${#AARS[@]} == 1 ]] || { printf 'Expected one release AAR, found %s\n' "${#AARS[@]}" >&2; exit 1; }
DEST="$ROOT/out/incoming-audio/ringrtc-incoming-audio-arm64.aar"
cp "${AARS[0]}" "$DEST"
python3 "$ROOT/tools/incoming-audio/verify_aar.py" "$DEST" > "$ROOT/out/incoming-audio/native-verification.json"
{
  printf 'RingRTC commit: '; git rev-parse HEAD
  printf 'WebRTC commit: '; git -C src/webrtc/src rev-parse HEAD
  printf 'Feature commit: '; git -C "$ROOT" rev-parse HEAD
} > "$ROOT/out/incoming-audio/source-revisions.txt"
sha256sum "$DEST" > "$DEST.sha256"
echo "Verified native AAR: $DEST"
