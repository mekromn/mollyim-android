/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio;

import androidx.annotation.Keep;

/** Optional native ABI. Never reports availability for an unpatched RingRTC. */
@Keep
public final class IncomingAudioBridge {
  private static boolean checked;
  private static volatile boolean available;
  private IncomingAudioBridge() {}

  public static synchronized boolean isAvailable() {
    if (!checked) {
      checked = true;
      try {
        System.loadLibrary("ringrtc");
        available = nativeVersion() == 1;
      } catch (LinkageError | SecurityException ignored) {
        available = false;
      }
    }
    return available;
  }

  public static boolean apply(float[] p) {
    if (p.length != 24 || !isAvailable()) return false;
    try {
      return nativeApply(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8],p[9],p[10],p[11],
                         p[12],p[13],p[14],p[15],p[16],p[17],p[18],p[19],p[20],p[21],p[22],p[23]);
    } catch (LinkageError ignored) {
      available = false;
      return false;
    }
  }

  public static void reset() {
    if (!isAvailable()) return;
    try { nativeReset(); } catch (LinkageError ignored) { available = false; }
  }

  /** Six numerical meters only; never copies or retains call audio. */
  public static long readMeters(float[] meters) {
    if (meters.length < 6 || !isAvailable()) return -1;
    try {
      for (int i = 0; i < 6; ++i) meters[i] = nativeMeter(i);
      return nativeFrames();
    } catch (LinkageError ignored) {
      available = false;
      return -1;
    }
  }

  private static native int nativeVersion();
  private static native boolean nativeApply(float p0,float p1,float p2,float p3,float p4,float p5,
      float p6,float p7,float p8,float p9,float p10,float p11,float p12,float p13,float p14,float p15,
      float p16,float p17,float p18,float p19,float p20,float p21,float p22,float p23);
  private static native void nativeReset();
  private static native float nativeMeter(int index);
  private static native long nativeFrames();
}
