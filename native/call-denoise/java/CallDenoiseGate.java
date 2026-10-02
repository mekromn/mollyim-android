/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.ringrtc;

import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.webrtc.PeerConnectionFactory;

/** Native call ownership, not account authorization. Never invoked by audio callbacks. */
public final class CallDenoiseGate {
  private static final WeakHashMap<PeerConnectionFactory, Long> FACTORIES = new WeakHashMap<>();
  private static long activeOwner;
  private CallDenoiseGate() {}

  /** Serializes the native construction handshake across all CallManagers. */
  static synchronized PeerConnectionFactory createFactory(Supplier<PeerConnectionFactory> builder) {
    long token = nativeBeginFactory();
    PeerConnectionFactory factory = null;
    try {
      factory = builder.get();
      return factory;
    } finally {
      boolean verified = nativeFinishFactory(token);
      if (factory != null && verified) FACTORIES.put(factory, token);
      if (!verified) Log.w("CallDenoiseGate", "Audio transport binding unavailable; retaining ordinary calling");
    }
  }

  static synchronized long begin(PeerConnectionFactory factory) {
    Long token = FACTORIES.get(factory);
    if (token == null) return 0;
    long owner = nativeNewOwner();
    if (!nativeBegin(owner, token)) return 0;
    activeOwner = owner;
    return owner;
  }

  static synchronized void mute(long owner, boolean muted, boolean remote) {
    if (owner != 0) nativeGate(owner, !muted, remote ? 1 : 0);
  }

  static synchronized void end(long owner) {
    if (owner != 0) nativeEnd(owner);
    if (activeOwner == owner) activeOwner = 0;
  }

  /** A physical audio-device notification invalidates buffered audio, not mute state. */
  public static synchronized void routeChanged() {
    if (activeOwner != 0) nativeInvalidate(activeOwner, 4);
  }

  private static native long nativeBeginFactory();
  private static native boolean nativeFinishFactory(long token);
  private static native long nativeNewOwner();
  private static native boolean nativeBegin(long owner, long factory);
  private static native boolean nativeEnd(long owner);
  private static native boolean nativeGate(long owner, boolean allowed, int reason);
  private static native boolean nativeInvalidate(long owner, int reason);
}
