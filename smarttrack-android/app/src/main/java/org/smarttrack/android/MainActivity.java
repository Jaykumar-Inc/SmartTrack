package org.smarttrack.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.time.Instant;
import java.util.*;
import org.json.*;

public class MainActivity extends AppCompatActivity {
  private LinearLayout layout, content;
  private TextView status, freshness;
  private Spinner devicesSpinner;
  private ApiClient api;
  private String selected, page = "Dashboard";
  private JSONArray devices = new JSONArray(), history = new JSONArray(), alerts = new JSONArray();
  private JSONObject latest;
  private String pendingExport;
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean active = false, loading = false, alertsEnabled = true;
  private boolean linkHealthy;
  private List<String> displayedDevices = Collections.emptyList();
  private int generation = 0, interval = 3;
  private final Set<Long> notified = new HashSet<>();
  private final Runnable ageTicker =
      new Runnable() {
        public void run() {
          if (active && api != null) {
            freshness();
            main.postDelayed(this, 1000);
          }
        }
      };
  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (active && api != null) {
            api.updates(
                () ->
                    main.post(
                        () -> {
                          if (active) refresh();
                        }));
            refresh();
            freshness();
            main.postDelayed(this, interval * 1000L);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    interval = getPreferences(0).getInt("interval", 3);
    alertsEnabled = getPreferences(0).getBoolean("alerts", true);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "smarttrack-alerts", "Wearable alerts", NotificationManager.IMPORTANCE_DEFAULT));
    login();
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
    if (api != null) {
      main.post(ticker);
      main.post(ageTicker);
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    active = false;
    linkHealthy = false;
    main.removeCallbacks(ticker);
    main.removeCallbacks(ageTicker);
    generation++;
    loading = false;
    if (api != null) api.cancel();
  }

  @Override
  protected void onDestroy() {
    if (api != null) api.close();
    super.onDestroy();
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(Color.rgb(226, 237, 248));
    t.setPadding(0, dp(7), 0, dp(7));
    return t;
  }

  private TextInputEditText input(LinearLayout parent, String label, String value, boolean secret) {
    TextInputLayout frame = new TextInputLayout(this);
    frame.setHint(label);
    TextInputEditText field = new TextInputEditText(frame.getContext());
    field.setSingleLine(true);
    field.setText(value);
    if (secret) field.setInputType(129);
    frame.addView(field);
    parent.addView(frame, new LinearLayout.LayoutParams(-1, -2));
    return field;
  }

  private MaterialButton button(LinearLayout parent, String label, Runnable action) {
    MaterialButton b = new MaterialButton(this);
    b.setText(label);
    b.setOnClickListener(v -> action.run());
    parent.addView(b);
    return b;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(20), dp(12), dp(20), dp(16));
    return l;
  }

  private void login() {
    if (api != null) {
      api.close();
      api = null;
    }
    generation++;
    loading = false;
    layout = column();
    setContentView(layout);
    layout.addView(text("SmartTrack", 30));
    layout.addView(text("Wi-Fi | Shared health, motion and chip temperature history", 15));
    var server =
        input(
            layout,
            "Server URL from SHOW_LOGIN.cmd",
            getPreferences(0).getString("server", "http://192.168.137.1:8080"),
            false);
    var username =
        input(layout, "Username", getPreferences(0).getString("username", "smarttrack"), false);
    var password = input(layout, "Password", "", true);
    TextView message =
        text("Connect this phone and ESP32 to the laptop hotspot JAY (2.4 GHz).", 14);
    layout.addView(message);
    button(
        layout,
        "Sign in",
        () -> {
          ApiClient candidate;
          try {
            candidate =
                new ApiClient(
                    server.getText().toString(),
                    username.getText().toString(),
                    password.getText().toString());
          } catch (Exception error) {
            message.setText(error.getMessage());
            return;
          }
          message.setText("Connecting to server...");
          candidate.request(
              "GET",
              "/auth/me",
              null,
              (result, error) ->
                  main.post(
                      () -> {
                        if (!active) {
                          candidate.close();
                          return;
                        }
                        if (error != null) {
                          candidate.close();
                          message.setText(error.getMessage());
                          return;
                        }
                        getPreferences(0)
                            .edit()
                            .putString("server", server.getText().toString())
                            .putString("username", username.getText().toString())
                            .apply();
                        password.setText("");
                        openClient(candidate);
                      }));
        });
    button(
        layout,
        "Open Wi-Fi settings",
        () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
    layout.addView(
        text(
            "Run START_SMARTTRACK.cmd on the laptop. Use the same login on phone and desktop."
                + " Monitoring data is shared through the local backend. Educational prototype.",
            13));
  }

  private void openClient(ApiClient candidate) {
    if (api != null) api.close();
    api = candidate;
    generation++;
    loading = false;
    shell();
    main.removeCallbacks(ticker);
    main.post(ticker);
    main.removeCallbacks(ageTicker);
    main.post(ageTicker);
  }

  private void shell() {
    layout = column();
    setContentView(layout);
    layout.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          layout.setPadding(
              dp(16),
              insets.getSystemWindowInsetTop() + dp(8),
              dp(16),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    layout.addView(text("SmartTrack", 28));
    status = text("Connecting…", 12);
    layout.addView(status);
    devicesSpinner = new Spinner(this);
    layout.addView(devicesSpinner);
    devicesSpinner.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            JSONObject d = devices.optJSONObject(pos);
            String next = d == null ? null : d.optString("deviceId");
            if (!Objects.equals(next, selected)) {
              selected = next;
              latest = null;
              history = new JSONArray();
              alerts = new JSONArray();
              generation++;
              loading = false;
              render();
              refresh();
            }
          }
        });
    HorizontalScrollView nav = new HorizontalScrollView(this);
    LinearLayout row = new LinearLayout(this);
    for (String item :
        new String[] {
          "Dashboard", "Health", "Temperature", "Activity", "Alerts", "Connection", "Settings"
        })
      button(
          row,
          item,
          () -> {
            page = item;
            render();
          });
    nav.addView(row);
    layout.addView(nav);
    ScrollView scroll = new ScrollView(this);
    content = column();
    scroll.addView(content);
    layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    freshness = text("No readings", 12);
    layout.addView(freshness);
    render();
  }

  private interface Success {
    void accept(Object value) throws Exception;
  }

  private void call(String method, String path, JSONObject body, Success success) {
    if (api == null) return;
    int token = generation;
    api.request(
        method,
        path,
        body,
        (r, e) ->
            main.post(
                () -> {
                  if (!active || token != generation) return;
                  if (e != null) {
                    error(e);
                    return;
                  }
                  try {
                    success.accept(r);
                  } catch (Exception ex) {
                    error(ex);
                  }
                }));
  }

  private void refresh() {
    if (api == null || loading || !active) return;
    loading = true;
    int token = generation;
    String id = selected;
    api.request(
        "GET",
        "/devices",
        null,
        (r, e) ->
            main.post(
                () -> {
                  if (token != generation || !active) return;
                  if (e != null) {
                    loading = false;
                    error(e);
                    return;
                  }
                  devices = (JSONArray) r;
                  ArrayList<String> names = new ArrayList<>();
                  for (int i = 0; i < devices.length(); i++) {
                    JSONObject d = devices.optJSONObject(i);
                    names.add(
                        (d.optBoolean("simulated") ? "SIMULATED • " : "")
                            + d.optString("name")
                            + (d.optBoolean("online") ? " • Online" : " • Offline"));
                  }
                  if (devicesSpinner.getAdapter() == null || !displayedDevices.equals(names)) {
                    displayedDevices = new ArrayList<>(names);
                    devicesSpinner.setAdapter(
                        new ArrayAdapter<>(
                            this, android.R.layout.simple_spinner_dropdown_item, names));
                    for (int i = 0; i < devices.length(); i++)
                      if (devices.optJSONObject(i).optString("deviceId").equals(selected))
                        devicesSpinner.setSelection(i);
                  }
                  if (id == null) {
                    loading = false;
                    status.setText(
                        devices.length() == 0
                            ? "Waiting for the first real Wi-Fi reading."
                            : "Select a device");
                    return;
                  }
                  api.request(
                      "GET",
                      "/devices/" + id + "/history?limit=120",
                      null,
                      (h, he) ->
                          main.post(
                              () -> {
                                if (token != generation || !active) return;
                                if (he != null) {
                                  loading = false;
                                  error(he);
                                  return;
                                }
                                history = (JSONArray) h;
                                latest = history.optJSONObject(0);
                                api.request(
                                    "GET",
                                    "/devices/" + id + "/alerts",
                                    null,
                                    (a, ae) ->
                                        main.post(
                                            () -> {
                                              if (token != generation || !active) return;
                                              loading = false;
                                              if (ae != null) {
                                                error(ae);
                                                return;
                                              }
                                              alerts = (JSONArray) a;
                                              linkHealthy = api.isConnected();
                                              status.setText(api.connectionStatus());
                                              freshness();
                                              notifyAlerts();
                                              if (!page.equals("Settings")) render();
                                            }));
                              }));
                }));
  }

  private JSONObject part(String name) {
    JSONObject p = latest == null ? null : latest.optJSONObject(name);
    return p == null ? new JSONObject() : p;
  }

  private String number(JSONObject object, String field, String suffix) {
    if (object.isNull(field) || !object.has(field)) return "Unavailable";
    return String.format(Locale.ROOT, "%.1f%s", object.optDouble(field), suffix);
  }

  private void freshness() {
    if (freshness == null) return;
    if (api != null && status != null) status.setText(api.connectionStatus());
    if (latest == null) {
      freshness.setText("No readings yet");
      return;
    }
    boolean stale = true;
    try {
      stale =
          Instant.parse(latest.getString("receivedAt")).isBefore(Instant.now().minusSeconds(10));
      if (!latest.isNull("capturedAt"))
        stale |=
            Instant.parse(latest.getString("capturedAt")).isBefore(Instant.now().minusSeconds(10));
    } catch (Exception ignored) {
    }
    freshness.setText(
        (latest.optBoolean("simulated") ? "SIMULATED • " : "")
            + (stale || !linkHealthy || api == null || !api.isConnected()
                ? "STALE / HISTORICAL"
                : latest.optBoolean("simulated") ? "STREAMING" : "LIVE")
            + "\nReceived "
            + latest.optString("receivedAt"));
  }

  private void card(String label, String value, String detail) {
    MaterialCardView c = new MaterialCardView(this);
    c.setRadius(dp(18));
    c.setStrokeWidth(dp(1));
    c.setStrokeColor(Color.rgb(42, 65, 86));
    c.setCardElevation(dp(2));
    c.setCardBackgroundColor(Color.rgb(20, 36, 58));
    LinearLayout inner = column();
    TextView heading = text(label, 12);
    heading.setTextColor(Color.rgb(84, 220, 199));
    heading.setLetterSpacing(.08f);
    inner.addView(heading);
    inner.addView(text(value, 26));
    inner.addView(text(detail, 12));
    c.addView(inner);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, 0, 0, dp(14));
    content.addView(c, p);
  }

  private void render() {
    if (content == null) return;
    content.removeAllViews();
    content.addView(text(page, 25));
    switch (page) {
      case "Connection":
        card("WI-FI SERVER", api.isConnected() ? "Connected" : "Offline", api.diagnostics());
        button(
            content,
            "Check server connection",
            () -> {
              api.reconnectNow();
              render();
            });
        button(
            content,
            "Wi-Fi settings",
            () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        content.addView(
            text(
                "Use the server URL and login from SHOW_LOGIN.cmd. Phone and desktop share the same"
                    + " readings and history.",
                14));
        break;
      case "Settings":
        settings();
        break;
      case "Alerts":
        alertRows();
        break;
      case "Health":
        health();
        break;
      case "Temperature":
        temperature();
        break;
      case "Activity":
        activity();
        break;
      default:
        dashboard();
    }
  }

  private void dashboard() {
    JSONObject h = part("health");
    card("HEART RATE", number(h, "bpm", " bpm"), h.optString("quality", "Unavailable"));
    card("ESTIMATED SpO₂", number(h, "spo2", " %"), "Educational estimate");
    card(
        "CHIP TEMPERATURE",
        number(part("temperature"), "celsius", " C"),
        part("temperature").optString("quality", "UNAVAILABLE") + " | LSM6DS3 internal");
    card(
        "ACTIVITY",
        part("motion").optString("activity", "Unavailable"),
        "Experimental classification");
    button(content, "Refresh now", this::refresh);
    content.addView(text("Recent alerts", 20));
    alertRows();
  }

  private void exportReadings() {
    pendingExport = ReadingCsv.create(history);
    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    save.addCategory(Intent.CATEGORY_OPENABLE);
    save.setType("text/csv");
    save.putExtra(Intent.EXTRA_TITLE, "SmartTrack-readings.csv");
    startActivityForResult(save, 210);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 210 || result != RESULT_OK || data == null || data.getData() == null) return;
    try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
      if (out == null) throw new java.io.IOException("Cannot open destination");
      out.write(
          (pendingExport == null ? ReadingCsv.create(history) : pendingExport)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Toast.makeText(this, "Displayed readings exported", Toast.LENGTH_LONG).show();
    } catch (Exception error) {
      error(error);
    } finally {
      pendingExport = null;
    }
  }

  private void health() {
    button(content, "Export displayed readings as CSV", this::exportReadings);
    JSONObject h = part("health");
    card(
        "SIGNAL QUALITY",
        h.optString("quality", "Unavailable"),
        "Finger present: " + h.optBoolean("fingerPresent"));
    content.addView(text("Heart rate history (bpm)", 18));
    content.addView(
        new TraceView(this, history, "bpm", false), new LinearLayout.LayoutParams(-1, dp(230)));
    content.addView(text("Estimated SpO₂ history (%)", 18));
    content.addView(
        new TraceView(this, history, "spo2", false), new LinearLayout.LayoutParams(-1, dp(230)));
    content.addView(text("Latest 120 samples • missing values are gaps", 12));
    for (int i = 0; i < Math.min(20, history.length()); i++) {
      JSONObject r = history.optJSONObject(i);
      content.addView(
          text(
              r.optString("receivedAt")
                  + "\n"
                  + number(r.optJSONObject("health"), "bpm", " bpm")
                  + " • "
                  + number(r.optJSONObject("health"), "spo2", " %"),
              12));
    }
  }

  private boolean fahrenheit;

  private void temperature() {
    JSONObject t = part("temperature");
    card(
        "INTERNAL CHIP TEMPERATURE",
        t.isNull("celsius")
            ? "Unavailable"
            : String.format(
                Locale.ROOT,
                "%.1f %s",
                convertTemperature(t.optDouble("celsius")),
                fahrenheit ? "F" : "C"),
        t.optString("quality", "UNAVAILABLE")
            + " | Sample age "
            + (t.isNull("sampleAgeMs") ? "--" : t.optLong("sampleAgeMs"))
            + " ms");
    content.addView(
        text(
            "LSM6DS3 chip temperature, not body temperature. Board heat and self-heating affect"
                + " readings.",
            14));
    button(
        content,
        fahrenheit ? "Show Celsius" : "Show Fahrenheit",
        () -> {
          fahrenheit = !fahrenheit;
          render();
        });
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    int count = 0;
    for (int i = 0; i < history.length(); i++) {
      JSONObject row = history.optJSONObject(i);
      JSONObject v = row == null ? null : row.optJSONObject("temperature");
      if (v != null && "GOOD".equals(v.optString("quality")) && !v.isNull("celsius")) {
        double c = convertTemperature(v.optDouble("celsius"));
        min = Math.min(min, c);
        max = Math.max(max, c);
        count++;
      }
    }
    card(
        "DISPLAYED HISTORY WINDOW",
        count == 0
            ? "No valid samples"
            : String.format(
                Locale.ROOT, "Min %.1f | Max %.1f %s", min, max, fahrenheit ? "F" : "C"),
        count + " valid samples; chart gaps indicate missing data");
    content.addView(
        new TraceView(this, history, "celsius", fahrenheit),
        new LinearLayout.LayoutParams(-1, dp(260)));
    for (int i = 0; i < Math.min(history.length(), 30); i++) {
      JSONObject r = history.optJSONObject(i);
      JSONObject v = r.optJSONObject("temperature");
      content.addView(
          text(
              r.optString("receivedAt")
                  + " | "
                  + (v == null || v.isNull("celsius")
                      ? "--"
                      : String.format(
                          Locale.ROOT,
                          "%.1f %s",
                          convertTemperature(v.optDouble("celsius")),
                          fahrenheit ? "F" : "C"))
                  + " | "
                  + (v == null ? "UNAVAILABLE" : v.optString("quality")),
              12));
    }
  }

  private double convertTemperature(double c) {
    return fahrenheit ? c * 1.8 + 32 : c;
  }

  private void activity() {
    JSONObject m = part("motion");
    card(
        "LSM6DS3",
        m.optString("activity", "UNAVAILABLE"),
        m.optString("quality", "UNAVAILABLE") + " • " + m.optString("calibration", "UNVERIFIED"));
    card(
        "FALL STATE",
        m.optString("fallState", "NORMAL"),
        "Experimental algorithm • Event " + m.optLong("eventId"));
    for (String f : new String[] {"ax", "ay", "az", "gx", "gy", "gz", "pitch", "roll", "magnitude"})
      card(
          f.toUpperCase(Locale.ROOT),
          m.isNull(f) ? "Unavailable" : String.format(Locale.ROOT, "%.3f", m.optDouble(f)),
          f.equals("magnitude") || f.startsWith("a")
              ? "g"
              : f.equals("temperature") ? "°C" : f.startsWith("g") ? "degrees/s" : "degrees");
    content.addView(text("Inactive: " + m.optBoolean("inactive"), 14));
    content.addView(
        new TraceView(this, history, "magnitude", false),
        new LinearLayout.LayoutParams(-1, dp(240)));
    button(content, "Cancel fall event", () -> deviceCommand("cancel"));
    button(content, "Calibrate flat +Z up", () -> deviceCommand("calibrate"));
    content.addView(text("Impact history", 20));
    long prior = -1;
    for (int i = 0; i < history.length(); i++) {
      JSONObject r = history.optJSONObject(i), motion = r.optJSONObject("motion");
      if (motion != null && motion.optBoolean("impact") && motion.optLong("impactCount") != prior) {
        prior = motion.optLong("impactCount");
        content.addView(text(r.optString("receivedAt") + " • Impact #" + prior, 12));
      }
    }
  }

  private void deviceCommand(String command) {
    if (selected != null)
      call(
          "POST",
          "/devices/" + selected + "/commands/" + command,
          null,
          r ->
              Toast.makeText(
                      this,
                      "Command queued for the watch. It will fetch the update over Wi-Fi.",
                      Toast.LENGTH_LONG)
                  .show());
  }

  private void watchSettings() {
    content.addView(text("Watch settings", 20));
    content.addView(
        text(
            "Settings and commands are saved on the shared server. The watch fetches updates over"
                + " Wi-Fi.",
            13));
    if (selected != null) {
      var sensitivity = input(content, "Sensitivity (0.5–2)", "1.0", false);
      var impact = input(content, "Impact threshold g (2–6)", "2.5", false);
      var low = input(content, "Low-g threshold (0.2–0.8)", "0.6", false);
      var inactivity = input(content, "Inactivity ms (1000–10000)", "2000", false);
      var cooldown = input(content, "Cooldown ms (10000–120000)", "20000", false);
      String device = selected;
      call(
          "GET",
          "/devices/" + device + "/settings",
          null,
          r -> {
            JSONObject c = (JSONObject) r;
            sensitivity.setText(c.optString("sensitivity"));
            impact.setText(c.optString("impactG"));
            low.setText(c.optString("lowG"));
            inactivity.setText(c.optString("inactivityMs"));
            cooldown.setText(c.optString("cooldownMs"));
          });
      button(
          content,
          "Save motion sensitivity",
          () -> {
            try {
              JSONObject c = new JSONObject();
              c.put("sensitivity", Double.parseDouble(sensitivity.getText().toString()));
              c.put("impactG", Double.parseDouble(impact.getText().toString()));
              c.put("lowG", Double.parseDouble(low.getText().toString()));
              c.put("inactivityMs", Long.parseLong(inactivity.getText().toString()));
              c.put("cooldownMs", Long.parseLong(cooldown.getText().toString()));
              call(
                  "PUT",
                  "/devices/" + device + "/settings",
                  c,
                  r ->
                      Toast.makeText(
                              this,
                              "Settings saved; the watch will fetch them over Wi-Fi.",
                              Toast.LENGTH_LONG)
                          .show());
            } catch (Exception ex) {
              error(ex);
            }
          });
    }
  }

  private void alertRows() {
    if (alerts.length() == 0) {
      content.addView(text("No alerts in this device's history.", 14));
      return;
    }
    for (int i = 0; i < alerts.length(); i++) {
      JSONObject a = alerts.optJSONObject(i);
      boolean ack = !a.isNull("acknowledgedAt");
      card(
          a.optString("severity") + " • " + (ack ? "Acknowledged" : "Active"),
          a.optString("type"),
          a.optString("message") + "\n" + a.optString("createdAt"));
      if (!ack)
        button(
            content,
            "Acknowledge",
            () ->
                call(
                    "POST",
                    "/devices/" + selected + "/alerts/" + a.optLong("id") + "/ack",
                    null,
                    r -> refresh()));
    }
  }

  private void settings() {
    watchSettings();
    if (selected != null) {
      JSONObject current = new JSONObject();
      for (int i = 0; i < devices.length(); i++)
        if (selected.equals(devices.optJSONObject(i).optString("deviceId")))
          current = devices.optJSONObject(i);
      var low =
          input(
              content,
              "Shared heart-rate low alert (bpm)",
              current.optString("minBpm", "45"),
              false);
      var high =
          input(
              content,
              "Shared heart-rate high alert (bpm)",
              current.optString("maxBpm", "130"),
              false);
      var oxygen =
          input(
              content,
              "Shared estimated SpO2 alert (%)",
              current.optString("minSpo2", "92"),
              false);
      button(
          content,
          "Save shared alert thresholds",
          () -> {
            try {
              JSONObject limits =
                  new JSONObject()
                      .put("minBpm", Double.parseDouble(low.getText().toString()))
                      .put("maxBpm", Double.parseDouble(high.getText().toString()))
                      .put("minSpo2", Double.parseDouble(oxygen.getText().toString()));
              call(
                  "PUT",
                  "/devices/" + selected + "/thresholds",
                  limits,
                  value ->
                      Toast.makeText(this, "Shared thresholds saved", Toast.LENGTH_SHORT).show());
            } catch (Exception failure) {
              error(failure);
            }
          });
      content.addView(
          text("Alert thresholds are shared through the server and apply to both apps.", 12));
    }
    var seconds = input(content, "Refresh seconds (1–30)", Integer.toString(interval), false);
    seconds.setInputType(2);
    MaterialSwitch notifications = new MaterialSwitch(this);
    notifications.setText("Active app alert notifications");
    notifications.setChecked(alertsEnabled);
    content.addView(notifications);
    button(
        content,
        "Save settings",
        () -> {
          try {
            int n = Integer.parseInt(seconds.getText().toString());
            if (n < 1 || n > 30) throw new IllegalArgumentException("Refresh must be 1–30 seconds");
            interval = n;
            alertsEnabled = notifications.isChecked();
            getPreferences(0)
                .edit()
                .putInt("interval", interval)
                .putBoolean("alerts", alertsEnabled)
                .apply();
            if (alertsEnabled
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
              requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 101);
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
          } catch (Exception e) {
            error(e);
          }
        });
    button(
        content,
        "Logout",
        () -> {
          main.removeCallbacks(ticker);
          main.removeCallbacks(ageTicker);
          generation++;
          api.close();
          api = null;
          selected = null;
          devices = new JSONArray();
          history = new JSONArray();
          alerts = new JSONArray();
          latest = null;
          loading = false;
          content = null;
          login();
        });
    content.addView(
        text(
            "Monitoring pauses when this app is backgrounded. No guaranteed background alerts."
                + " Temperature comes from the wearable; this app does not request the phone's"
                + " location.",
            13));
  }

  private void notifyAlerts() {
    if (!alertsEnabled || api == null || !api.isConnected()) return;
    NotificationManager manager = getSystemService(NotificationManager.class);
    for (int i = 0; i < alerts.length(); i++) {
      JSONObject a = alerts.optJSONObject(i);
      try {
        if (Instant.parse(a.getString("createdAt")).isBefore(Instant.now().minusSeconds(10)))
          continue;
      } catch (Exception invalidTime) {
        continue;
      }
      long id = a.optLong("id");
      if (a.isNull("acknowledgedAt") && notified.add(id)) {
        status.setText("ALERT • " + a.optString("message"));
        if (Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
          PendingIntent open =
              PendingIntent.getActivity(
                  this,
                  0,
                  new Intent(this, MainActivity.class),
                  PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
          manager.notify(
              (int) id,
              new Notification.Builder(this, "smarttrack-alerts")
                  .setSmallIcon(android.R.drawable.ic_dialog_alert)
                  .setContentTitle(
                      (latest != null && latest.optBoolean("simulated") ? "SIMULATED • " : "")
                          + "SmartTrack "
                          + a.optString("severity"))
                  .setContentText(a.optString("message"))
                  .setContentIntent(open)
                  .setAutoCancel(true)
                  .build());
        }
      }
    }
  }

  private void error(Exception e) {
    linkHealthy = false;
    if (status != null) status.setText("Connection/request error: " + e.getMessage());
    if (freshness != null) freshness.setText("REQUEST FAILED • Historical values only");
    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
  }
}
