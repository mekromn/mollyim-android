/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio;

import androidx.annotation.Keep;
import java.util.Arrays;

/** Optional control ABI. The RingRTC initializer owns native library loading. */
@Keep
public final class CallDenoiseBridge {
  private CallDenoiseBridge() {}

  public static boolean available() {
    try { return nativeVersion() == 1; }
    catch (Exception | LinkageError ignored) { return false; }
  }

  public static boolean paths(String library, String standard, String lowLatency, String mobileFused) {
    if (library == null || standard == null || lowLatency == null || mobileFused == null) return false;
    try { return nativePaths(library, standard, lowLatency, mobileFused); }
    catch (Exception | LinkageError ignored) { return false; }
  }

  public static boolean apply(int direction, boolean enabled, int model, float attenuation,
                              boolean postFilter, float beta, float minimum, float erb, float df) {
    try { return nativeApply(direction, enabled, model, attenuation, postFilter, beta, minimum, erb, df); }
    catch (Exception | LinkageError ignored) { return false; }
  }

  public static void bypass(int direction, boolean bypassed) {
    try { nativeBypass(direction, bypassed); }
    catch (Exception | LinkageError ignored) { }
  }

  public static void retry(int direction) {
    try { nativeRetry(direction); }
    catch (Exception | LinkageError ignored) { }
  }

  public static boolean status(int direction, float[] values) {
    if (values == null || values.length != 16) return false;
    Arrays.fill(values, 0);
    try { return nativeStatus(direction, values); }
    catch (Exception | LinkageError ignored) { return false; }
  }

  /** Compatible with the stock AAR used by ordinary upstream build variants. */
  public static void routeChanged() {
    try {
      Class.forName("org.signal.ringrtc.CallDenoiseGate").getMethod("routeChanged").invoke(null);
    } catch (Exception | LinkageError ignored) { }
  }

  private static native int nativeVersion();
  private static native boolean nativePaths(String library, String standard, String lowLatency, String mobileFused);
  private static native boolean nativeApply(int direction, boolean enabled, int model, float attenuation,
                                           boolean postFilter, float beta, float minimum, float erb, float df);
  private static native void nativeBypass(int direction, boolean bypassed);
  private static native void nativeRetry(int direction);
  private static native boolean nativeStatus(int direction, float[] values);
}
