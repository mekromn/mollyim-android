/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.ringrtc;

import android.content.Context;
import androidx.annotation.Keep;
import org.webrtc.PeerConnectionFactory;

/** Local call-engine endpoint. Construct/use/close on a controller executor,
 * never on Android's UI/audio callbacks. No PeerConnection is created. */
@Keep
public final class MockCallSession implements AutoCloseable {
  private final long handle;
  private PeerConnectionFactory factory;
  private volatile boolean revoked;

  private MockCallSession(long handle, PeerConnectionFactory factory) {
    this.handle = handle;
    this.factory = factory;
  }

  public static MockCallSession create(Context context, AudioConfig config) throws CallException {
    synchronized (CallDenoiseGate.class) {
      if (context == null || config == null || nativeVersion() != 1) throw new CallException("Mock call engine unavailable");
      long handle = nativePrepare();
      if (handle == 0) throw new CallException("Audio resources are busy");
      PeerConnectionFactory factory = null;
      boolean finished = false;
      try {
        factory = CallManager.createMockPeerConnectionFactory(config);
        finished = nativeFinish(handle);
        if (!finished) throw new CallException("The local call path could not be verified");
        return new MockCallSession(handle, factory);
      } finally {
        if (!finished) {
          nativeFinish(handle); // clear a failed construction scope, never bind another owner
          nativeRevoke(handle);
          if (factory != null) factory.dispose();
          nativeRelease(handle);
        }
      }
    }
  }

  static void requireIdleHardware() throws CallException {
    if (!nativePreempt()) throw new CallException("Local audio resources did not stop within the safety deadline");
  }

  public long configure(float[] values) { return revoked ? 0 : nativeConfigure(handle, values); }
  public long command(int operation, long a, long b, long c, long d) { return revoked && operation != 2 ? 0 : nativeCommand(handle, operation, a, b, c, d); }
  public long load(int direction, int rate, int channels, short[] pcm) { return revoked ? 0 : nativeLoad(handle, direction, rate, channels, pcm); }
  public boolean status(float[] values) { return nativeStatus(handle, values); }
  public int drain(int tap, long[] metadata, float[] pcm) { return revoked ? 0 : nativeDrain(handle, tap, metadata, pcm); }

  /** Nonblocking microphone/source/tap revocation. Hardware stop is posted. */
  public void revoke() { revoked = true; nativeRevoke(handle); }

  /** Call only after status[4] confirms hardware has stopped. Model destruction
   * can take longer, so disposal is separate from real-call hardware handoff. */
  @Override public void close() {
    revoke();
    float[] values = new float[128];
    if (!nativeStatus(handle, values) || values[4] == 0) throw new IllegalStateException("Await hardware retirement before disposal");
    if (factory != null) { factory.dispose(); factory = null; }
    nativeRelease(handle);
  }

  private static native int nativeVersion();
  private static native long nativePrepare();
  private static native boolean nativeFinish(long handle);
  private static native void nativeRevoke(long handle);
  private static native boolean nativePreempt();
  private static native boolean nativeRelease(long handle);
  private static native long nativeCommand(long handle, int op, long a, long b, long c, long d);
  private static native long nativeConfigure(long handle, float[] values);
  private static native long nativeLoad(long handle, int direction, int rate, int channels, short[] pcm);
  private static native boolean nativeStatus(long handle, float[] values);
  private static native int nativeDrain(long handle, int tap, long[] metadata, float[] pcm);
}
