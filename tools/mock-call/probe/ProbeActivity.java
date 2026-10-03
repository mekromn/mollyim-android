/* SPDX-License-Identifier: AGPL-3.0-only */
package com.mekromn.mollymockprobe;

import android.app.Activity;
import android.os.Bundle;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import org.signal.ringrtc.AudioConfig;
import org.signal.ringrtc.CallManager;
import org.signal.ringrtc.MockCallSession;

/** Test-only process; uses the exact shipped ARM64 AAR, no recorder substitute,
 * no account or network PeerConnection. Emulator silence still must traverse
 * actual ADM capture callbacks and the native before/after recording taps. */
public final class ProbeActivity extends Activity {
  private final StringBuilder evidence = new StringBuilder();
  private void note(String text) {
    evidence.append(text).append('\n');
    android.util.Log.i("MollyDeviceProbe", text);
  }
  private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private float[] status(MockCallSession session) {
    float[] result = new float[128];
    check(session.status(result), "native status unavailable");
    return result;
  }
  private void await(MockCallSession session, long command) throws Exception {
    check(command > 0, "command rejected");
    long end = System.nanoTime() + 2_000_000_000L;
    do {
      float[] s = status(session);
      if (s[3] >= command) {
        check(s[1] != 5, "native operation failed error=" + s[2]);
        return;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < end);
    throw new AssertionError("command did not complete");
  }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView view = new TextView(this);
    view.setText("CI only — real RingRTC microphone-start regression");
    setContentView(view);
    new Thread(() -> {
      boolean success = true;
      try {
        CallManager.initialize(getApplicationContext(), null, new HashMap<>());
        for (boolean oboe : new boolean[]{false, true}) {
          try { runBackend(oboe); } catch (Throwable failure) {
            success = false;
            note("FAIL backend=" + (oboe ? "Oboe" : "Java") + " " + android.util.Log.getStackTraceString(failure));
          }
        }
      } catch (Throwable failure) {
        success = false; note("FAIL init " + android.util.Log.getStackTraceString(failure));
      }
      note(success ? "ALL_BACKENDS_PASS" : "PROBE_FAILED");
      try {
        Files.write(new File(getFilesDir(), "result.txt").toPath(), evidence.toString().getBytes(StandardCharsets.UTF_8));
      } catch (Exception failure) { android.util.Log.e("MollyDeviceProbe", "Cannot save evidence", failure); }
    }, "real-adm-probe").start();
  }
  private void runBackend(boolean oboe) throws Exception {
    AudioManager audio = getSystemService(AudioManager.class);
    int oldMode = audio.getMode();
    MockCallSession session = null;
    try {
      note("BEGIN " + (oboe ? "Oboe" : "Java") + " mode=" + oldMode + " micMuted=" + audio.isMicrophoneMute());
      AudioConfig config = new AudioConfig();
      config.useOboe = oboe;
      // Request the usual software processing in this deterministic smoke test;
      // not a performance/acoustic/Pixel-routing claim.
      config.useSoftwareAec = true; config.useSoftwareNs = true;
      config.useInputVoiceComm = true;
      session = MockCallSession.create(getApplicationContext(), config);
      note("factory/endpoint verified");
      audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
      AudioDeviceInfo chosen = null;
      for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) chosen = device;
      }
      check(chosen != null, "emulator has no communication speaker");
      check(audio.setCommunicationDevice(chosen), "communication route rejected");
      note("route requested=" + chosen.getType());
      await(session, session.command(4, 1, 0, 0, 0));
      await(session, session.command(6, 0, 120000, 0, 0));
      await(session, session.command(1, 1, 1, 0, 7));
      note("start acknowledged");
      int[] blocks = new int[3];
      long[] metadata = new long[16]; float[] pcm = new float[3840];
      long end = System.nanoTime() + 3_000_000_000L;
      while (System.nanoTime() < end) {
        for (int tap = 0; tap < 3; tap++) {
          for (int n = 0; n < 32; n++) {
            int size = session.drain(tap, metadata, pcm);
            check(size >= 0, "tap error " + tap);
            if (size == 0) break;
            blocks[tap]++;
          }
        }
        Thread.sleep(5);
      }
      float[] s = status(session);
      note("capture taps=" + java.util.Arrays.toString(blocks) + " state=" + s[1] + " error=" + s[2] + " dropped=" + s[11] + " mode=" + audio.getMode());
      for (int tap = 0; tap < 3; tap++) check(blocks[tap] >= 10, "missing actual capture at tap " + tap);
      await(session, session.command(2, 0, 0, 0, 0));
      note("PASS actual ADM -> backend tap -> sent input -> sent output; no network call");
    } finally {
      if (session != null) {
        session.revoke();
        for (int i=0; i<200 && status(session)[4]==0; i++) Thread.sleep(10);
        session.close();
      }
      audio.clearCommunicationDevice(); audio.setMode(oldMode);
    }
  }
}
