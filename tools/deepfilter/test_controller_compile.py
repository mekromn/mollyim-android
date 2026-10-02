#!/usr/bin/env python3
"""Compile Android-facing controller glue against an API fixture, not an SDK/emulator."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'app/src/main/java/org/thoughtcrime/securesms/webrtc/audio'
kotlin=Path(shutil.which('kotlinc')).resolve().parent.parent
coroutines=kotlin/'lib/kotlinx-coroutines-core-jvm.jar'
if not coroutines.is_file():raise SystemExit('Install the Kotlin distribution coroutines JAR for this host-only fixture')
STUBS={
 'androidx/annotation/Keep.java':'package androidx.annotation; public @interface Keep {}',
 'android/content/Context.java':'''package android.content; import java.io.File; import android.content.res.AssetManager; import android.content.pm.ApplicationInfo; public abstract class Context { public static final int MODE_PRIVATE=0; public abstract Context getApplicationContext(); public abstract SharedPreferences getSharedPreferences(String n,int m); public abstract File getNoBackupFilesDir(); public abstract AssetManager getAssets(); public abstract ApplicationInfo getApplicationInfo(); }''',
 'android/content/SharedPreferences.java':'''package android.content; import java.util.Map; public interface SharedPreferences { Map<String,?> getAll(); Editor edit(); interface Editor { Editor putString(String k,String v); void apply(); } }''',
 'android/content/res/AssetManager.java':'''package android.content.res; import java.io.*; public abstract class AssetManager { public abstract InputStream open(String path) throws IOException; }''',
 'android/content/pm/ApplicationInfo.java':'''package android.content.pm; public class ApplicationInfo { public String nativeLibraryDir; }''',
}
with tempfile.TemporaryDirectory() as d:
 p=Path(d);classes=p/'classes';classes.mkdir()
 for name,text in STUBS.items():
  path=p/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
 subprocess.run(['javac','-d',str(classes),*[str(p/n) for n in STUBS],str(A/'CallDenoiseBridge.java')],check=True)
 sources=[A/(n+'.kt') for n in ('CallDenoiseSettings','DenoiseCoordinator','ModelAssetStore','DeepFilterAssets','CallDenoiseController')]
 subprocess.run(['kotlinc',*[str(s) for s in sources],'-classpath',str(classes)+os.pathsep+str(coroutines),'-d',str(p/'glue.jar')],check=True)
print('Android-facing controller Kotlin/Java compilation PASS (API fixture, not Android SDK).')
