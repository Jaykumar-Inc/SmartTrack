package io.smarttrack.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.smarttrack.contract.Telemetry;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalStateTest {
  @Test
  void simulatedCachedSignalNeverLooksLive() {
    var now = Instant.now();
    var t =
        new Telemetry(
            1,
            "sim",
            java.util.UUID.randomUUID().toString(),
            0,
            now,
            Telemetry.TimestampQuality.SYSTEM,
            0,
            Telemetry.Source.SIMULATOR,
            null,
            null,
            null,
            null,
            List.of());
    assertEquals(
        "SIMULATED · CACHED · backend unavailable", SignalState.describe(t, now, now, false));
    assertTrue(SignalState.describe(t, now.minusSeconds(60), now, true).contains("OFFLINE"));
  }
}
