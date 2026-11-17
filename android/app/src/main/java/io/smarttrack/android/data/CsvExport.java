package io.smarttrack.android.data;

import io.smarttrack.contract.Telemetry;
import java.time.Instant;

public final class CsvExport {
  private CsvExport() {}

  public static final String HEADER =
      "device,received_at,measured_at,source,quality,bpm,spo2_percent,acceleration_g,pitch_deg,roll_deg,gps_fix,latitude,longitude\r\n";

  public static String cell(Object value) {
    if (value == null) return "";
    String text = value.toString();
    if (value instanceof CharSequence && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0)
      text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  public static String row(Telemetry t, long received) {
    Object[] cells = {
      t.deviceId(),
      Instant.ofEpochMilli(received),
      t.timestamp(),
      t.source(),
      t.health().quality(),
      t.health().heartRateBpm(),
      t.health().spo2Percent(),
      t.motion().magnitudeG(),
      t.motion().pitchDeg(),
      t.motion().rollDeg(),
      t.gps().fix(),
      t.gps().fix() ? t.gps().latitude() : null,
      t.gps().fix() ? t.gps().longitude() : null
    };
    return java.util.Arrays.stream(cells)
            .map(CsvExport::cell)
            .collect(java.util.stream.Collectors.joining(","))
        + "\r\n";
  }
}
