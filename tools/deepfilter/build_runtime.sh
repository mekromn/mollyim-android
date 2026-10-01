#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${ANDROID_HOME:?Android SDK required}"
NDK="$ANDROID_HOME/ndk/28.0.13004108"
TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TOOLS/aarch64-linux-android27-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android27-clang"
export CC_aarch64_linux_android="$TOOLS/aarch64-linux-android27-clang"
export AR_aarch64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
rustup target add --toolchain 1.88.0 aarch64-linux-android
cargo +1.88.0 build --manifest-path "$ROOT/native/deepfilter-runtime/Cargo.toml" --release --locked --target aarch64-linux-android
mkdir -p "$ROOT/out/runtime-android"
cp "$ROOT/native/deepfilter-runtime/target/aarch64-linux-android/release/libmolly_deepfilter.so" "$ROOT/out/runtime-android/"
python3 "$ROOT/tools/deepfilter/verify_runtime.py" "$ROOT/out/runtime-android/libmolly_deepfilter.so" > "$ROOT/out/runtime-android/verification.json"
sha256sum "$ROOT/out/runtime-android/libmolly_deepfilter.so" > "$ROOT/out/runtime-android/library.sha256"
git -C "$ROOT" rev-parse HEAD > "$ROOT/out/runtime-android/source-revision.txt"
