#!/usr/bin/env bash
# Runs the APK's actual Android ARM64 library under QEMU + pinned AOSP Bionic.
# Not an Android UI emulator, phone-call test, or Pixel performance benchmark.
set -euo pipefail
APK=$(realpath "$1")
REFERENCE=$(realpath "$2")
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/out/arm64-execution"
mkdir -p "$WORK"
python3 - "$APK" "$WORK" <<'PY'
import base64,hashlib,io,json,pathlib,sys,urllib.request,zipfile
apk=pathlib.Path(sys.argv[1]);work=pathlib.Path(sys.argv[2])
with zipfile.ZipFile(apk) as z:
 for name in ['lib/arm64-v8a/libmolly_deepfilter.so','assets/deepfilter/DeepFilterNet3_onnx.tar.gz','assets/deepfilter/DeepFilterNet3_ll_onnx.tar.gz','assets/deepfilter/DeepFilterNet3_onnx_mobile.tar.gz']:
  (work/pathlib.Path(name).name).write_bytes(z.read(name))
url='https://android.googlesource.com/platform/prebuilts/runtime/+/98857cec0a64b4524a3d1acfa9848dd7a8f3487b/mainline/runtime/apex/com.android.runtime-arm64.apex?format=TEXT'
with urllib.request.urlopen(url,timeout=120) as r:apex=base64.b64decode(r.read())
(work/'bionic.apex').write_bytes(apex)
with zipfile.ZipFile(io.BytesIO(apex)) as z:(work/'apex_payload.img').write_bytes(z.read('apex_payload.img'))
(work/'provenance.json').write_text(json.dumps({'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bionic_source':url,'bionic_apex_sha256':hashlib.sha256(apex).hexdigest(),'test_boundary':'Android ARM64 ELF with AOSP Bionic via QEMU user-mode; no Android app or physical call execution'},indent=2))
PY
mkdir -p "$WORK/bionic"
debugfs -R "rdump / $WORK/bionic" "$WORK/apex_payload.img" > "$WORK/extraction.log" 2>&1
test -f "$WORK/bionic/bin/linker64"
CC="$ANDROID_HOME/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang"
"$CC" -std=gnu11 -O2 -Wall -Wextra -Werror -I "$ROOT/native/deepfilter-runtime/include" "$ROOT/tools/deepfilter/runtime_smoke.c" -ldl -lm -o "$WORK/runtime-smoke"
chmod +x "$WORK/bionic/bin/linker64"
for entry in 'standard DeepFilterNet3_onnx.tar.gz standard.f32 1440' 'low_latency DeepFilterNet3_ll_onnx.tar.gz low_latency.f32 480' 'mobile_fused DeepFilterNet3_onnx_mobile.tar.gz - 480'; do
  read -r name model golden delay <<< "$entry"
  golden_path="$golden"
  if [[ "$golden" != "-" ]]; then golden_path="$REFERENCE/vectors/$golden"; fi
  timeout 360 qemu-aarch64 -cpu max -E "LD_LIBRARY_PATH=$WORK/bionic/lib64/bionic" "$WORK/bionic/bin/linker64" "$WORK/runtime-smoke" "$WORK/libmolly_deepfilter.so" "$WORK/$model" "$REFERENCE/vectors/input.f32" "$golden_path" "$delay" | tee "$WORK/$name.json"
done
