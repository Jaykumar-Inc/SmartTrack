package org.smarttrack.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;

/** Offline native Canvas charts; no WebView or online tiles. */
final class TraceView extends View {
  private final JSONArray rows;
  private final String field;
  private final boolean fahrenheit;
  private final Paint paint = new Paint(3);

  TraceView(Context c, JSONArray rows, String field, boolean fahrenheit) {
    super(c);
    this.rows = rows;
    this.field = field;
    this.fahrenheit = fahrenheit;
    setMinimumHeight(600);
    setContentDescription("Historical " + field + " chart");
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    int w = getWidth(), h = getHeight();
    c.drawColor(Color.rgb(17, 30, 50));
    paint.setStrokeWidth(1);
    paint.setColor(Color.rgb(38, 60, 87));
    for (int i = 1; i < 8; i++) {
      c.drawLine(i * w / 8f, 0, i * w / 8f, h, paint);
      c.drawLine(0, i * h / 8f, w, i * h / 8f, paint);
    }
    double minX = Double.POSITIVE_INFINITY,
        maxX = Double.NEGATIVE_INFINITY,
        minY = Double.POSITIVE_INFINITY,
        maxY = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i),
          p =
              row.optJSONObject(
                  field.equals("celsius")
                      ? "temperature"
                      : field.equals("magnitude") ? "motion" : "health");
      if (p == null || p.isNull(field)) continue;
      double x = rows.length() - 1 - i,
          y =
              field.equals("celsius") && fahrenheit
                  ? p.optDouble(field) * 1.8 + 32
                  : p.optDouble(field);
      if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    paint.setTextSize(30);
    paint.setColor(Color.LTGRAY);
    if (!Double.isFinite(minX)) {
      c.drawText("No valid data in this window", 24, 50, paint);
      return;
    }
    c.drawText("History | oldest to newest", 24, 40, paint);
    c.drawText(String.format(java.util.Locale.ROOT, "%.4f – %.4f", minY, maxY), 24, h - 16, paint);
    double sx = Math.max(1, maxX - minX), sy = Math.max(1, maxY - minY);
    Path path = new Path();
    boolean open = false;
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject p =
          rows.optJSONObject(i)
              .optJSONObject(
                  field.equals("celsius")
                      ? "temperature"
                      : field.equals("magnitude") ? "motion" : "health");
      if (p == null || p.isNull(field)) {
        open = false;
        continue;
      }
      double rawX = rows.length() - 1 - i,
          rawY =
              field.equals("celsius") && fahrenheit
                  ? p.optDouble(field) * 1.8 + 32
                  : p.optDouble(field);
      float x = 30 + (float) ((rawX - minX) / sx) * (w - 60),
          y = h - 60 - (float) ((rawY - minY) / sy) * (h - 130);
      if (open) path.lineTo(x, y);
      else {
        path.moveTo(x, y);
        open = true;
      }
    }
    paint.setColor(Color.CYAN);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(4);
    c.drawPath(path, paint);
    paint.setStyle(Paint.Style.FILL);
  }
}
