package org.smarttrack.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;

public final class ReadingState {
  private ReadingState() {}

  public static boolean stale(JsonNode reading, Instant now) {
    if (reading == null || reading.isMissingNode()) return true;
    try {
      if (Instant.parse(reading.path("receivedAt").asText()).isBefore(now.minusSeconds(10)))
        return true;
      var capture = reading.path("capturedAt");
      return !capture.isNull()
          && !capture.isMissingNode()
          && Instant.parse(capture.asText()).isBefore(now.minusSeconds(10));
    } catch (Exception e) {
      return true;
    }
  }

  public static String value(JsonNode node, String name, String suffix) {
    var v = node.path(name);
    return v.isNumber()
        ? String.format(java.util.Locale.ROOT, "%.1f%s", v.asDouble(), suffix)
        : "Unavailable";
  }
}
