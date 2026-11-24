package org.smarttrack.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReadingStateTest {
  @Test
  void staleCaptureNeverAppearsLive() throws Exception {
    var json = new ObjectMapper();
    Instant now = Instant.parse("2026-11-15T12:00:00Z");
    assertTrue(
        ReadingState.stale(
            json.readTree(
                "{\"receivedAt\":\"2026-11-15T12:00:00Z\",\"capturedAt\":\"2026-11-15T11:00:00Z\"}"),
            now));
    assertFalse(
        ReadingState.stale(
            json.readTree("{\"receivedAt\":\"2026-11-15T12:00:00Z\",\"capturedAt\":null}"), now));
    assertTrue(ReadingState.stale(null, now));
  }
}
