package io.smarttrack.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Density-aware chart of actual samples. Missing estimates break the line. */
public class SignalChart extends View {
  private final List<Double> values;
  private final String unit;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density, scaledDensity;
  private final double low, high;
  private final long valid;

  public SignalChart(Context context) {
    this(context, List.of(), "bpm");
  }

  public SignalChart(Context context, List<Double> samples, String unit) {
    super(context);
    this.values = new ArrayList<>(samples);
    this.unit = unit;
    density = getResources().getDisplayMetrics().density;
    scaledDensity = getResources().getDisplayMetrics().scaledDensity;
    valid = values.stream().filter(Objects::nonNull).count();
    double minimum =
        values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).min().orElse(0);
    double maximum =
        values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(1);
    double margin = Math.max(unit.contains("%") ? .5 : 1, (maximum - minimum) * .15);
    low = minimum - margin;
    high = maximum + margin;
    setContentDescription(
        valid == 0
            ? "No valid " + unit + " samples"
            : valid + " valid " + unit + " samples; oldest to latest");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setTextSize(10 * scaledDensity);
    paint.setStrokeWidth(density);
    if (valid == 0) {
      paint.setColor(MobileUi.MUTED);
      paint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("No valid samples in this range", getWidth() / 2f, getHeight() / 2f, paint);
      return;
    }
    float left = 45 * density,
        right = getWidth() - 8 * density,
        top = 12 * density,
        bottom = getHeight() - 29 * density;
    if (right <= left || bottom <= top) return;
    for (int i = 0; i < 3; i++) {
      float y = top + (bottom - top) * i / 2;
      paint.setColor(MobileUi.BORDER);
      canvas.drawLine(left, y, right, y, paint);
      paint.setColor(MobileUi.MUTED);
      paint.setTextAlign(Paint.Align.RIGHT);
      canvas.drawText(
          String.format(
              Locale.ROOT, unit.contains("%") ? "%.1f" : "%.0f", high - (high - low) * i / 2),
          left - 8 * density,
          y + 3 * scaledDensity,
          paint);
    }
    paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText("Older", left, getHeight() - 6 * density, paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText("Latest", right, getHeight() - 6 * density, paint);
    paint.setColor(MobileUi.ACCENT);
    paint.setStrokeWidth(2 * density);
    paint.setStrokeCap(Paint.Cap.ROUND);
    Float previousX = null, previousY = null;
    for (int i = 0; i < values.size(); i++) {
      Double value = values.get(i);
      if (value == null) {
        previousX = null;
        previousY = null;
        continue;
      }
      float x = left + (right - left) * i / Math.max(1, values.size() - 1),
          y = bottom - (float) ((value - low) / (high - low)) * (bottom - top);
      if (previousX == null) canvas.drawCircle(x, y, 2 * density, paint);
      else canvas.drawLine(previousX, previousY, x, y, paint);
      previousX = x;
      previousY = y;
    }
  }
}
