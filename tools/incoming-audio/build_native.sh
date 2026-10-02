#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${INCOMING_AUDIO_WORK:-"${RUNNER_TEMP:-/tmp}/molly-incoming-audio"}
PHASE=${1:-all}
case "$PHASE" in all|prepare|build) ;; *) echo 'Usage: build_native.sh [all|prepare|build]' >&2; exit 2;; esac
DEPOT_REV=57d50b8656bfed9e42adf8de1d549ba4229cc909
PREPARED="$WORK/prepared-native-v1.txt"
mkdir -p "$WORK" "$ROOT/out/incoming-audio"
for tool in git python3 rustup java protoc; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
test -d "$ANDROID_NDK_HOME" || { echo "Install NDK 28.0.13004108 first" >&2; exit 1; }
if [[ "$PHASE" == build && ! -f "$PREPARED" ]]; then
  echo 'Run prepare first; no completed native workspace checkpoint exists' >&2
  exit 1
fi
# Metadata for Molly's temporary WebRTC cherry-picks, not APK signing.
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-Molly native builder}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-builder@localhost}"
export PYTHONUNBUFFERED=1
export DEPOT_TOOLS_UPDATE=0
if [[ ! -d "$WORK/depot_tools/.git" ]]; then
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$WORK/depot_tools"
fi
if [[ $(git -C "$WORK/depot_tools" rev-parse HEAD) != "$DEPOT_REV" ]]; then
  git -C "$WORK/depot_tools" fetch --depth 1 origin "$DEPOT_REV"
  git -C "$WORK/depot_tools" checkout --detach "$DEPOT_REV"
fi
export PATH="$WORK/depot_tools:$PATH"
# Match RingRTC's Dockerfile.android. gclient alone did not initialize the
# Python wrapper required by GN; fail here before another expensive checkout.
"$WORK/depot_tools/ensure_bootstrap"
"$WORK/depot_tools/python-bin/python3" --version
if [[ ! -d "$WORK/ringrtc/.git" ]]; then
  git clone --depth 1 --branch v2.69.5-1 https://github.com/mollyim/ringrtc.git "$WORK/ringrtc"
fi
cd "$WORK/ringrtc"
[[ $(git rev-parse refs/tags/v2.69.5-1) == e05d1475a96806acad749da8030ea4b73cc5fdb4 ]] || { echo 'Pinned RingRTC tag changed; refusing to build' >&2; exit 1; }
rustup show
rustup target add aarch64-linux-android
checkpoint() { printf '%s|%s|%s\n' "$(git rev-parse HEAD)" "$(git -C src/webrtc/src rev-parse HEAD)" "$DEPOT_REV"; }
if [[ "$PHASE" != build ]]; then
  ./bin/prepare-workspace android
  checkpoint > "$PREPARED"
fi
if [[ "$PHASE" == prepare ]]; then
  echo 'Prepared pinned workspace; feature patches have not been applied.'
  du -sh "$WORK"
  exit 0
fi
[[ $(cat "$PREPARED") == "$(checkpoint)" ]] || { echo 'Prepared source revisions changed; refusing cached workspace' >&2; exit 1; }
(cd src/webrtc/src && gn --version)
if [[ ${MOLLY_MOCK_BUILD:-0} == 1 ]]; then
  python3 "$ROOT/tools/mock-call/patch_ringrtc.py" "$WORK/ringrtc"
elif [[ ${MOLLY_DEEPFILTER_BUILD:-0} == 1 ]]; then
  python3 "$ROOT/tools/deepfilter/patch_ringrtc.py" "$WORK/ringrtc"
else
  python3 "$ROOT/tools/incoming-audio/patch_ringrtc.py" "$WORK/ringrtc"
fi
# Siso uses -offline, not Ninja's unsupported -jN spelling.
# Its local worker count defaults to the available CPUs.
./bin/build-aar --release-build --arch arm64 -j "${BUILD_JOBS:-2}" --extra-ninja-flags=-offline
# Support both the Gradle buildDir override and the Android module default.
mapfile -t AARS < <(find "$WORK/ringrtc/out" "$WORK/ringrtc/src/android" -type f \
  \( -name 'ringrtc-android-release.aar' -o -name 'ringrtc-android-2.69.5-1.aar' \) | sort -u)
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
