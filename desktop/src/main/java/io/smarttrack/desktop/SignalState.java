package io.smarttrack.desktop;

import io.smarttrack.contract.Telemetry;
import java.time.*;

public final class SignalState {
  private SignalState() {}

  public static String describe(Telemetry t, Instant received, Instant now, boolean connected) {
    String origin = t.source() == Telemetry.Source.SIMULATOR ? "SIMULATED · " : "HARDWARE · ";
    if (!connected) return origin + "CACHED · backend unavailable";
    long age = Math.max(0, Duration.between(received, now).getSeconds());
    if (age > 15) return origin + "OFFLINE · received " + age + "s ago";
    if (t.timestamp() == null) return origin + "TIME UNVERIFIED";
    if (Duration.between(t.timestamp(), now).getSeconds() > 10) return origin + "DELAYED";
    return origin + "LIVE · received " + age + "s ago";
  }
}
