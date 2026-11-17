package io.smarttrack.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.*;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;

/** Shared native components keep spacing, touch targets and reading density consistent. */
final class MobileUi {
  static final int TEXT = 0xfff2f5f7,
      MUTED = 0xff9ba9b6,
      ACCENT = 0xff4cb7a5,
      SURFACE = 0xff121a23,
      BORDER = 0xff293541;
  private final Context context;

  MobileUi(Context context) {
    this.context = context;
  }

  int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setLineSpacing(dp(3), 1);
    return view;
  }

  LinearLayout card() {
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(16), dp(16), dp(16));
    GradientDrawable background = new GradientDrawable();
    background.setColor(SURFACE);
    background.setCornerRadius(dp(12));
    background.setStroke(dp(1), BORDER);
    box.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(12);
    box.setLayoutParams(params);
    return box;
  }

  LinearLayout metric(String title, String value, String detail) {
    LinearLayout box = card();
    TextView label = text(title, 11, MUTED);
    label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    label.setLetterSpacing(.05f);
    box.addView(label);
    TextView number = text(value, value.length() > 18 ? 19 : 30, TEXT);
    number.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    number.setFontFeatureSettings("tnum");
    number.setMaxLines(2);
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
        number, 18, value.length() > 18 ? 22 : 30, 1, TypedValue.COMPLEX_UNIT_SP);
    LinearLayout.LayoutParams numberParams =
        new LinearLayout.LayoutParams(-1, dp(value.length() > 18 ? 52 : 48));
    numberParams.topMargin = dp(10);
    numberParams.bottomMargin = dp(6);
    box.addView(number, numberParams);
    box.addView(text(detail, 12, MUTED));
    return box;
  }

  void row(LinearLayout parent, String label, String value) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(7), 0, dp(7));
    TextView name = text(label, 13, MUTED);
    TextView reading = text(value, 14, TEXT);
    reading.setGravity(Gravity.END);
    reading.setFontFeatureSettings("tnum");
    reading.setMaxLines(2);
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1),
        right = new LinearLayout.LayoutParams(0, -2, 1.2f);
    right.leftMargin = dp(12);
    row.addView(name, left);
    row.addView(reading, right);
    parent.addView(row);
  }

  MaterialButton button(String title, Runnable action, boolean primary) {
    MaterialButton button =
        new MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    button.setText(title);
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setCornerRadius(dp(9));
    button.setMinHeight(dp(48));
    button.setInsetTop(0);
    button.setInsetBottom(0);
    button.setStrokeWidth(primary ? 0 : dp(1));
    button.setStrokeColor(ColorStateList.valueOf(BORDER));
    button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : SURFACE));
    button.setTextColor(primary ? 0xff0b1016 : TEXT);
    button.setPadding(dp(16), dp(10), dp(16), dp(10));
    button.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(4);
    params.bottomMargin = dp(8);
    button.setLayoutParams(params);
    return button;
  }
}
