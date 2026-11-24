package org.smarttrack.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exports the actual displayed history, preserving missing measurements as blanks. */
final class ReadingCsv {
  private ReadingCsv() {}

  static String create(JSONArray readings) {
    StringBuilder csv =
        new StringBuilder(
            "received_at,captured_at,bpm,spo2,health_quality,activity,chip_temperature_c,temperature_quality,temperature_sample_age_ms\r\n");
    for (int i = 0; i < readings.length(); i++) {
      JSONObject r = readings.optJSONObject(i);
      if (r == null) continue;
      JSONObject h = r.optJSONObject("health"),
          m = r.optJSONObject("motion"),
          t = r.optJSONObject("temperature");
      Object[] fields = {
        value(r, "receivedAt"),
        value(r, "capturedAt"),
        value(h, "bpm"),
        value(h, "spo2"),
        value(h, "quality"),
        value(m, "activity"),
        value(t, "celsius"),
        value(t, "quality"),
        value(t, "sampleAgeMs")
      };
      for (int f = 0; f < fields.length; f++) {
        if (f > 0) csv.append(',');
        csv.append(quote(fields[f]));
      }
      csv.append("\r\n");
    }
    return csv.toString();
  }

  private static Object value(JSONObject object, String field) {
    return object == null || object.isNull(field) ? "" : object.opt(field);
  }

  private static String quote(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    // Prevent spreadsheet formulas in text columns without altering numbers.
    if (!(value instanceof Number) && text.matches("^[=+@-].*")) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
