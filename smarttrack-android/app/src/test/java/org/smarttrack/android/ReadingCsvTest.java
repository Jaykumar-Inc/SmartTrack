package org.smarttrack.android;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ReadingCsvTest {
  @Test
  public void preservesNullMeasurementsAndChipTemperature() throws Exception {
    String csv =
        ReadingCsv.create(
            new JSONArray(
                "[{\"receivedAt\":\"2026-11-23T00:00:00Z\",\"health\":{\"bpm\":null,\"spo2\":null,\"quality\":\"NO_FINGER\"},\"temperature\":{\"celsius\":28.5,\"quality\":\"GOOD\"}}]"));
    assertTrue(csv.contains("\"\",\"\",\"NO_FINGER\""));
    assertTrue(csv.contains("\"28.5\",\"GOOD\""));
    assertFalse(csv.contains("null"));
  }

  @Test
  public void quotesAndNeutralizesTextFormulas() throws Exception {
    JSONObject row =
        new JSONObject()
            .put("receivedAt", "=1+2")
            .put("motion", new JSONObject().put("activity", "quoted, \"text\""));
    String csv = ReadingCsv.create(new JSONArray().put(row));
    assertTrue(csv.contains("\"'=1+2\""));
    assertTrue(csv.contains("\"quoted, \"\"text\"\"\""));
  }
}
