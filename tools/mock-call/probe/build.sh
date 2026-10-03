#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?}"
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT="$ROOT/out/device-probe"
SDK="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$OUT/aar" "$OUT/classes" "$OUT/dex"
unzip -q -o "$ROOT/out/native/ringrtc-mock-arm64.aar" -d "$OUT/aar"
cat > "$OUT/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mekromn.mollymockprobe">
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
<application android:label="CI native capture probe" android:debuggable="true" android:extractNativeLibs="true">
<activity android:name=".ProbeActivity" android:exported="true"/>
</application></manifest>
XML
javac -source 11 -target 11 -cp "$JAR:$OUT/aar/classes.jar" -d "$OUT/classes" "$ROOT/tools/mock-call/probe/ProbeActivity.java"
jar cf "$OUT/probe.jar" -C "$OUT/classes" .
"$SDK/d8" --release --min-api 31 --lib "$JAR" --output "$OUT/dex" "$OUT/aar/classes.jar" "$OUT/probe.jar"
"$SDK/aapt2" link -I "$JAR" --manifest "$OUT/AndroidManifest.xml" --min-sdk-version 31 --target-sdk-version 35 -o "$OUT/base.apk"
python3 - "$OUT" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'base.apk','a',zipfile.ZIP_DEFLATED) as z:
 for f in (p/'dex').glob('*.dex'):z.write(f,f.name)
 for f in (p/'aar/jni/arm64-v8a').glob('*.so'):z.write(f,'lib/arm64-v8a/'+f.name)
PY
# Ephemeral key belongs ONLY to this different-package CI probe. Never a Molly update.
keytool -genkeypair -keystore "$OUT/probe.jks" -storepass android -keypass android -alias ci-probe -dname 'CN=Disposable CI probe' -keyalg RSA -validity 2 >/dev/null 2>&1
"$SDK/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$SDK/apksigner" sign --ks "$OUT/probe.jks" --ks-pass pass:android --key-pass pass:android --out "$OUT/probe.apk" "$OUT/aligned.apk"
