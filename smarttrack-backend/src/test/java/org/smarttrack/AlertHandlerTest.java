package org.smarttrack;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.smarttrack.dto.Telemetry;
import org.smarttrack.entity.Device;
import org.smarttrack.service.*;

class AlertHandlerTest {
  @Test
  void poorSignalNeverTriggersHealthAlert() {
    var d = new Device();
    var t =
        new Telemetry(
            "x",
            1,
            null,
            false,
            new Telemetry.Health(null, null, false, "NO_FINGER"),
            new Telemetry.Motion(null, null, null, null, null, null, null, false, "UNAVAILABLE"),
            new Telemetry.Temperature(null, "UNAVAILABLE", "LSM6DS3_INTERNAL", "C", null));
    assertTrue(new HealthAlertHandler().evaluate(d, t).isEmpty());
    assertTrue(new FallAlertHandler().evaluate(d, t).isEmpty());
  }

  @Test
  void thresholdsProduceWarning() {
    var d = new Device();
    var t =
        new Telemetry(
            "x",
            1,
            null,
            false,
            new Telemetry.Health(160.0, 85.0, true, "GOOD"),
            new Telemetry.Motion(0.0, 0.0, 9.8, 0.0, 0.0, 0.0, 0.0, true, "POSSIBLE_FALL"),
            new Telemetry.Temperature(null, "UNAVAILABLE", "LSM6DS3_INTERNAL", "C", null));
    assertEquals(2, new HealthAlertHandler().evaluate(d, t).size());
    assertEquals("POSSIBLE_FALL", new FallAlertHandler().evaluate(d, t).getFirst().type());
  }
}
