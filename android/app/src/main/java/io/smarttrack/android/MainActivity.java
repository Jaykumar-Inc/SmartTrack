package io.smarttrack.android;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.*;
import com.google.gson.*;
import io.smarttrack.android.data.*;
import io.smarttrack.android.databinding.ActivityMainBinding;
import io.smarttrack.contract.Telemetry;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MainActivity extends AppCompatActivity {
  private ActivityMainBinding binding;
  private MonitorViewModel model;
  private String page = "Home";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean form, loginForm;
  private MobileUi ui;
  private TextView loginError, deviceConnectionState, diagnosticDetails;
  private final ActivityResultLauncher<String> csvFile =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/csv"),
          uri -> {
            if (uri == null) return;
            var rows = new ArrayList<>(model.selected());
            model.worker.execute(
                () -> {
                  try {
                    StringBuilder csv = new StringBuilder(CsvExport.HEADER);
                    for (var row : rows)
                      csv.append(
                          CsvExport.row(
                              Wire.decode(model.vault.decrypt(row.payload)), row.receivedAt));
                    try (var output = getContentResolver().openOutputStream(uri)) {
                      if (output == null)
                        throw new java.io.IOException("Selected file could not be opened");
                      output.write(
                          csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    handler.post(() -> toast("CSV exported"));
                  } catch (Exception failure) {
                    handler.post(() -> toast("Export failed: " + failure.getMessage()));
                  }
                });
          });
  private final List<MaterialButton> authButtons = new ArrayList<>();
  private final Runnable polling =
      new Runnable() {
        public void run() {
          model.poll();
          updateStatus();
          updateConnectionPanels();
          handler.postDelayed(this, 3000);
        }
      };
  private final ActivityResultLauncher<String[]> permissions =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(),
          result -> {
            if (result.values().stream().allMatch(Boolean::booleanValue)) scan();
            else toast("Bluetooth permission is needed for device discovery");
          });

  private final ActivityResultLauncher<Intent> bluetoothEnable =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK) handler.postDelayed(this::scan, 800);
            else toast("Bluetooth is off. Enable it to connect to the ESP32.");
          });

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    ui = new MobileUi(this);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    WindowCompat.getInsetsController(getWindow(), binding.root)
        .setAppearanceLightNavigationBars(false);
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.root,
        (v, insets) -> {
          var bar = insets.getInsets(WindowInsetsCompat.Type.systemBars());
          var keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
          v.setPadding(bar.left, bar.top, bar.right, Math.max(bar.bottom, keyboard.bottom));
          binding.navigation.setVisibility(
              insets.isVisible(WindowInsetsCompat.Type.ime()) ? View.GONE : View.VISIBLE);
          return insets;
        });
    model = new ViewModelProvider(this).get(MonitorViewModel.class);
    if (state != null) page = state.getString("page", "Home");
    binding.navigation.setOnItemSelectedListener(
        item -> {
          int id = item.getItemId();
          if (id == R.id.more) {
            showWorkspace();
            return false;
          }
          navigate(id == R.id.health ? "Health" : id == R.id.devices ? "Devices" : "Home");
          return true;
        });
    binding
        .navigation
        .getMenu()
        .findItem(
            page.equals("Home")
                ? R.id.home
                : page.equals("Health")
                    ? R.id.health
                    : page.equals("Devices") ? R.id.devices : R.id.more)
        .setChecked(true);
    binding.navigation.setOnItemReselectedListener(
        item -> {
          if (item.getItemId() == R.id.more) showWorkspace();
          else binding.scroll.smoothScrollTo(0, 0);
        });
    model.readings.observe(
        this,
        rows -> {
          if (!form) renderPreservingScroll();
        });
    model.devices.observe(
        this,
        rows -> {
          if (page.equals("Devices") && !form) renderPreservingScroll();
          else updateHeader();
        });
    model.discovered.observe(
        this,
        rows -> {
          if (page.equals("Devices") && !form) renderPreservingScroll();
          else updateHeader();
        });
    model.alerts.observe(
        this,
        rows -> {
          if (page.equals("Alerts")) renderPreservingScroll();
        });
    model.errors.observe(
        this,
        error -> {
          if (error != null) {
            if (loginForm && loginError != null) {
              loginError.setText(error);
              authButtons.forEach(b -> b.setEnabled(true));
            } else toast(error);
          }
        });
    model.sharing.observe(this, message -> updateConnectionPanels());
    model.status.observe(
        this,
        status -> {
          if (loginForm && status != null && status.startsWith("Signed in")) {
            navigate("Devices");
          } else {
            updateStatus();
            updateConnectionPanels();
          }
        });
    render();
    if (model.vault.token() != null) model.loadDevices();
  }

  @Override
  protected void onResume() {
    super.onResume();
    model.resumeBle();
    handler.post(polling);
  }

  @Override
  protected void onPause() {
    super.onPause();
    handler.removeCallbacks(polling);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);
  }

  private void navigate(String page) {
    this.page = page;
    form = false;
    loginForm = false;
    binding
        .navigation
        .getMenu()
        .findItem(
            page.equals("Home")
                ? R.id.home
                : page.equals("Health")
                    ? R.id.health
                    : page.equals("Devices") ? R.id.devices : R.id.more)
        .setChecked(true);
    binding.scroll.scrollTo(0, 0);
    render();
    if (page.equals("Devices") && model.vault.token() != null) model.loadDevices();
    if (page.equals("History") || page.equals("Health")) model.history();
    if (page.equals("Alerts")) model.loadAlerts();
  }

  private Telemetry latest() {
    var rows = model.selected();
    if (rows.isEmpty()) return null;
    try {
      return model.decode(rows.get(0));
    } catch (Exception e) {
      toast(e.getMessage());
      return null;
    }
  }

  private void showWorkspace() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("Workspace")
        .setItems(
            new String[] {"Motion", "Location", "Alerts", "History", "Settings", "Diagnostics"},
            (d, index) ->
                navigate(
                    new String[] {
                          "Motion", "Location", "Alerts", "History", "Settings", "Diagnostics"
                        }
                        [index]))
        .show();
  }

  private void renderPreservingScroll() {
    int position = binding.scroll.getScrollY();
    String expected = page;
    render();
    binding.scroll.post(
        () -> {
          if (expected.equals(page)) binding.scroll.scrollTo(0, position);
        });
  }

  private void updateHeader() {
    binding.toolbar.setTitle(page.equals("Home") ? "SmartTrack" : page);
    String name = model.device.isEmpty() ? "Clarity in every signal." : model.device;
    if (model.devices.getValue() != null)
      for (var value : model.devices.getValue()) {
        var device = value.getAsJsonObject();
        if (device.get("id").getAsString().equals(model.device)) {
          name = device.get("name").getAsString();
          break;
        }
      }
    if (name.equals(model.device) && name.length() > 24)
      name = "Device · " + name.substring(name.length() - 8);
    binding.toolbar.setSubtitle(name);
  }

  private void updateStatus() {
    if (model == null) return;
    Telemetry t = latest();
    String mode = model.mode.equals("BLE") ? "Nearby BLE" : "Backend";
    if (t == null) {
      binding.status.setText("AWAITING SIGNAL");
      binding.status.setTextColor(MobileUi.MUTED);
      binding.statusDetail.setText(
          mode + " · " + Objects.toString(model.status.getValue(), "Select a device"));
      return;
    }
    long age =
        Math.max(0, (System.currentTimeMillis() - model.selected().get(0).receivedAt) / 1000);
    String freshness =
        !model.connected
            ? "CACHED"
            : age > 15
                ? "OFFLINE"
                : t.timestamp() == null
                    ? "TIME UNVERIFIED"
                    : java.time.Duration.between(t.timestamp(), Instant.now()).getSeconds() > 10
                        ? "DELAYED"
                        : "LIVE";
    String provenance = t.source() == Telemetry.Source.SIMULATOR ? "SIMULATED · " : "HARDWARE · ";
    binding.status.setText(provenance + freshness);
    binding.status.setTextColor(freshness.equals("LIVE") ? MobileUi.ACCENT : 0xffdcaa57);
    String elapsed = age < 60 ? age + "s" : age < 3600 ? (age / 60) + " min" : (age / 3600) + " hr";
    binding.statusDetail.setText(
        mode + " · " + (age < 2 ? "Just received" : "Last signal " + elapsed + " ago"));
  }

  private void render() {
    if (form) return;
    binding.content.removeAllViews();
    updateHeader();
    updateStatus();
    Telemetry t = latest();
    if (page.equals("Devices")) {
      devices();
      return;
    }
    if (page.equals("Diagnostics")) {
      diagnostics();
      return;
    }
    if (page.equals("Settings")) {
      settings();
      return;
    }
    if (page.equals("Alerts")) {
      alerts();
      return;
    }
    if (t == null) {
      heading("Waiting for your first signal");
      paragraph(
          "Connect a nearby device over BLE, or sign in to your backend and select a registered"
              + " device.");
      action("Open devices", () -> navigate("Devices"));
      action(
          "Sign in to backend",
          () -> {
            model.setMode("BACKEND");
            login();
          });
      return;
    }
    switch (page) {
      case "Home":
        heading("Your signals");
        paragraph("A clear view of health, motion and position.");
        healthSummary(t);
        LinearLayout summary = card("Device snapshot");
        ui.row(summary, "Activity", t.motion().activity().name().replace('_', ' '));
        ui.row(summary, "Acceleration", num(t.motion().magnitudeG(), 2) + " g");
        ui.row(
            summary,
            "GPS",
            t.gps().fix()
                ? "Satellite fix"
                : t.gps().communicating() ? "Searching for fix" : "No receiver data");
        ui.row(summary, "Firmware", t.status().firmwareVersion());
        action("Review alerts", () -> navigate("Alerts"));
        break;
      case "Health":
        heading("Health signals");
        paragraph(
            "Experimental estimates · not medically validated. Invalid readings remain"
                + " unavailable.");
        healthSummary(t);
        chart(false);
        chart(true);
        break;
      case "Motion":
        heading("Motion & orientation");
        paragraph("Activity: " + t.motion().activity() + " · fall detection is experimental.");
        LinearLayout acceleration = card("Acceleration · g");
        ui.row(acceleration, "X axis", num(t.motion().axG(), 3));
        ui.row(acceleration, "Y axis", num(t.motion().ayG(), 3));
        ui.row(acceleration, "Z axis", num(t.motion().azG(), 3));
        LinearLayout angular = card("Angular velocity · °/s");
        ui.row(angular, "X axis", num(t.motion().gxDps(), 2));
        ui.row(angular, "Y axis", num(t.motion().gyDps(), 2));
        ui.row(angular, "Z axis", num(t.motion().gzDps(), 2));
        LinearLayout orientation = card("Orientation & detector");
        ui.row(
            orientation,
            "Pitch / roll",
            num(t.motion().pitchDeg(), 1) + "° / " + num(t.motion().rollDeg(), 1) + "°");
        ui.row(orientation, "Temperature", num(t.motion().temperatureC(), 1) + " °C");
        ui.row(orientation, "Fall state", t.motion().fallState().name());
        break;
      case "Location":
        heading("Satellite position");
        paragraph(t.gps().communicating() ? "Receiver communicating" : "No NMEA communication");
        metric(
            "CURRENT FIX",
            t.gps().fix()
                ? num(t.gps().latitude(), 6) + ", " + num(t.gps().longitude(), 6)
                : "Unavailable",
            "Satellites " + (t.gps().satellites() == null ? "unavailable" : t.gps().satellites()));
        paragraph(
            "Speed "
                + num(t.gps().speedKmh(), 1)
                + " km/h\nAltitude "
                + num(t.gps().altitudeM(), 1)
                + " m\nLast fix "
                + (t.gps().lastFixAt() == null ? "Never" : time(t.gps().lastFixAt())));
        if (t.gps().fix())
          action(
              "Open in OpenStreetMap",
              () ->
                  startActivity(
                      new Intent(
                          Intent.ACTION_VIEW,
                          Uri.parse(
                              "https://www.openstreetmap.org/?mlat="
                                  + t.gps().latitude()
                                  + "&mlon="
                                  + t.gps().longitude()
                                  + "#map=16/"
                                  + t.gps().latitude()
                                  + "/"
                                  + t.gps().longitude()))));
        break;
      case "History":
        heading("Recent history");
        paragraph(
            "Last 24 hours from backend; local view holds up to 500 recent samples across devices."
                + " Source and quality are preserved.");
        action("Refresh history", model::history);
        for (var row : model.selected().stream().limit(100).toList()) {
          Telemetry sample = model.decode(row);
          paragraph(
              time(Instant.ofEpochMilli(row.receivedAt))
                  + "  ·  "
                  + sample.source()
                  + "\n"
                  + num(sample.health().heartRateBpm(), 0)
                  + " bpm   "
                  + num(sample.health().spo2Percent(), 1)
                  + " %   "
                  + sample.health().quality());
        }
        break;
    }
  }

  private void devices() {
    heading("Your device connections");
    paragraph(
        "Use phone Bluetooth and PC USB together. To see the same readings through the backend,"
            + " sign in and link your BLE device.");
    LinearLayout connection = card("Connection status");
    deviceConnectionState =
        ui.text(
            Objects.toString(model.status.getValue(), "Awaiting connection"), 13, MobileUi.MUTED);
    connection.addView(deviceConnectionState);
    updateConnectionPanels();
    action(
        "Connect nearby ESP32",
        () -> {
          model.setMode("BLE");
          render();
          scanPermissions();
        });
    action(
        "Set up backend connection",
        () -> {
          model.setMode("BACKEND");
          login();
        });
    action("Open connection diagnostics", () -> navigate("Diagnostics"));
    if (!model.selected().isEmpty()) {
      var sample = latest();
      LinearLayout live = card("Latest reading / " + sample.source());
      ui.row(live, "Heart rate", num(sample.health().heartRateBpm(), 0) + " bpm");
      ui.row(live, "Acceleration", num(sample.motion().magnitudeG(), 2) + " g");
      ui.row(live, "Health signal", sample.health().quality().name().replace('_', ' '));
      action("View live readings", () -> navigate("Home"));
    }
    if (model.mode.equals("BLE")) {
      action("Discover nearby devices", this::scanPermissions);
      model
          .discovered
          .getValue()
          .forEach(
              (address, name) ->
                  action(
                      name + " · " + address,
                      () -> {
                        try {
                          model.connectBle(address);
                        } catch (Exception e) {
                          toast(e.getMessage());
                        }
                      }));
      action("Disconnect BLE", model::disconnectBle);
      String previousAddress = model.vault.preferences().getString("ble_address", "");
      if (!previousAddress.isEmpty())
        action(
            "Reconnect last ESP32",
            () -> {
              try {
                model.connectBle(previousAddress);
              } catch (Exception failure) {
                toast(failure.getMessage());
              }
            });
      if (model.vault.token() == null) action("Sign in for PC sharing", this::login);
      else {
        action("Link BLE device and share with PC", model::linkBleDevice);
        action("Share cached readings now", model::syncNow);
      }
      paragraph(
          "Connect here inside SmartTrack; pairing in Bluetooth settings alone does not start"
              + " monitoring. Enter the six-digit OLED code when Android asks. Monitoring may stop"
              + " when Android suspends the app.");
    } else {
      if (model.vault.token() == null) {
        action("Sign in / create account", this::login);
        return;
      }
      action("Refresh registry", model::loadDevices);
      for (var value : model.devices.getValue()) {
        JsonObject d = value.getAsJsonObject();
        LinearLayout registered = card(d.get("name").getAsString());
        TextView identity = ui.text(d.get("id").getAsString(), 12, MobileUi.MUTED);
        identity.setSingleLine(true);
        identity.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        identity.setContentDescription("Device ID " + d.get("id").getAsString());
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(-1, -2);
        identityParams.bottomMargin = dp(12);
        registered.addView(identity, identityParams);
        registered.addView(
            ui.button(
                "Monitor device",
                () -> {
                  model.choose(d.get("id").getAsString());
                  navigate("Home");
                },
                false));
      }
      action(
          "Register device",
          () -> {
            LinearLayout content = dialogLayout();
            TextInputEditText id = input(content, "Device ID", false, ""),
                name = input(content, "Name", false, "");
            new MaterialAlertDialogBuilder(this)
                .setTitle("Register device")
                .setView(content)
                .setPositiveButton(
                    "Register",
                    (d, w) ->
                        model.registerDevice(
                            id.getText().toString(),
                            name.getText().toString(),
                            secret ->
                                new MaterialAlertDialogBuilder(this)
                                    .setTitle("Token · shown once")
                                    .setMessage(
                                        secret + "\n\nStore securely and provision over serial.")
                                    .setPositiveButton("Close", null)
                                    .show()))
                .setNegativeButton("Cancel", null)
                .show();
          });
    }
  }

  private void login() {
    form = true;
    loginForm = true;
    authButtons.clear();
    binding.content.removeAllViews();
    binding.scroll.scrollTo(0, 0);
    heading("Your private workspace");
    paragraph(
        "Start Start-Backend.cmd on the laptop, then enter its phone URL. Use the same Wi-Fi, or"
            + " connect over BLE without an account.");
    TextInputEditText
        address = input(binding.content, "Backend address", false, model.backendAddress()),
        email = input(binding.content, "Email", false, ""),
        password = input(binding.content, "Password (12–72 characters)", true, "");
    loginError = ui.text("", 13, 0xffde7074);
    binding.content.addView(loginError);
    for (boolean create : new boolean[] {false, true}) {
      MaterialButton button =
          ui.button(
              create ? "Create account" : "Sign in",
              () -> {
                loginError.setText("Connecting…");
                authButtons.forEach(b -> b.setEnabled(false));
                model.authenticate(
                    address.getText().toString().trim(),
                    email.getText().toString().trim(),
                    password.getText().toString(),
                    create);
              },
              !create);
      authButtons.add(button);
      binding.content.addView(button);
    }
    action(
        "Test connection",
        () -> {
          loginError.setText("Checking backend...");
          model.checkConnection(
              address.getText().toString(),
              message -> {
                if (loginForm && loginError != null) loginError.setText(message);
              });
        });
    action(
        "Use nearby BLE without signing in",
        () -> {
          model.setMode("BLE");
          navigate("Devices");
          scanPermissions();
        });
    action(
        "Use USB backend address",
        () -> address.setText(io.smarttrack.contract.BackendEndpoint.DESKTOP));
    if (BackendSettings.emulator())
      action(
          "Use emulator backend address",
          () -> address.setText(io.smarttrack.contract.BackendEndpoint.EMULATOR));
    action("Cancel", () -> navigate("Devices"));
  }

  private void diagnostics() {
    heading("Connection diagnostics");
    paragraph(
        "This report contains connection state and sensor availability. It excludes passwords,"
            + " tokens, pairing codes and measured values.");
    LinearLayout panel = card("Current connection");
    diagnosticDetails = ui.text(model.connectionReport(), 12, MobileUi.MUTED);
    panel.addView(diagnosticDetails);
    action("Refresh diagnostics", this::updateConnectionPanels);
    action(
        "Copy connection report",
        () -> {
          var clipboard = getSystemService(android.content.ClipboardManager.class);
          clipboard.setPrimaryClip(
              ClipData.newPlainText("SmartTrack connection report", model.connectionReport()));
          toast("Connection report copied");
        });
    action(
        "Test saved backend",
        () ->
            model.checkConnection(
                model.backendAddress(),
                message -> {
                  toast(message);
                  updateConnectionPanels();
                }));
    action(
        "Bluetooth settings",
        () -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
    action(
        "App permissions",
        () ->
            startActivity(
                new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
    action("Export selected readings as CSV", () -> csvFile.launch("smarttrack-readings.csv"));
  }

  private void updateConnectionPanels() {
    if (model == null) return;
    if (deviceConnectionState != null && deviceConnectionState.isAttachedToWindow())
      deviceConnectionState.setText(
          Objects.toString(model.status.getValue(), "Awaiting connection")
              + "\n"
              + Objects.toString(model.sharing.getValue(), "Sharing is off"));
    if (diagnosticDetails != null && diagnosticDetails.isAttachedToWindow())
      diagnosticDetails.setText(model.connectionReport());
  }

  private void settings() {
    heading("Connection & privacy");
    form = true;
    TextInputEditText
        address = input(binding.content, "Backend address", false, model.backendAddress()),
        timezone =
            input(
                binding.content,
                "Display timezone",
                false,
                model.vault.preferences().getString("timezone", "Asia/Kolkata"));
    SwitchMaterial sync = new SwitchMaterial(this);
    sync.setText("Sync cached BLE readings to backend");
    sync.setChecked(model.vault.preferences().getBoolean("sync", false));
    binding.content.addView(sync);
    action(
        "Save settings",
        () -> {
          try {
            String next = address.getText().toString();
            next = io.smarttrack.contract.BackendEndpoint.normalize(next);
            Backend.create(next, null);
            ZoneId.of(timezone.getText().toString());
            if (!next.equals(model.backendAddress())) model.vault.token(null);
            model
                .vault
                .preferences()
                .edit()
                .putString("backend", next)
                .putString("timezone", timezone.getText().toString())
                .putBoolean("sync", sync.isChecked())
                .apply();
            if (sync.isChecked()) SyncWorker.enqueue(this);
            toast("Settings saved");
          } catch (Exception e) {
            toast(e.getMessage());
          }
        });
    action(
        "Test connection", () -> model.checkConnection(address.getText().toString(), this::toast));
    action("Export selected readings as CSV", () -> csvFile.launch("smarttrack-readings.csv"));
    if (model.mode.equals("BLE")) {
      action(
          "Synchronize device clock",
          () -> {
            try {
              model.ble.write(7, "{\"epochSeconds\":" + Instant.now().getEpochSecond() + "}");
            } catch (Exception e) {
              toast(e.getMessage());
            }
          });
      action(
          "Set BLE delivery interval",
          () -> {
            LinearLayout content = dialogLayout();
            TextInputEditText interval = input(content, "1000–60000 milliseconds", false, "1000");
            new MaterialAlertDialogBuilder(this)
                .setTitle("Delivery interval")
                .setView(content)
                .setPositiveButton(
                    "Apply",
                    (d, w) -> {
                      try {
                        int n = Integer.parseInt(interval.getText().toString());
                        if (n < 1000 || n > 60000)
                          throw new IllegalArgumentException("Use 1000–60000 ms");
                        model.ble.write(6, "{\"telemetryIntervalMs\":" + n + "}");
                      } catch (Exception e) {
                        toast(e.getMessage());
                      }
                    })
                .setNegativeButton("Cancel", null)
                .show();
          });
    }
    action(
        "Clear local measurement cache",
        () ->
            new MaterialAlertDialogBuilder(this)
                .setTitle("Clear local cache?")
                .setMessage("This also removes any BLE measurements not yet synchronized.")
                .setPositiveButton(
                    "Clear", (d, w) -> model.worker.execute(() -> model.db.readings().clear()))
                .setNegativeButton("Cancel", null)
                .show());
    action(
        "Sign out",
        () ->
            model.signOut(
                () -> {
                  toast("Signed out");
                  navigate("Devices");
                }));
    paragraph(
        "Local payloads and credentials are encrypted using Android Keystore. Cache retention: 7"
            + " days / 10,000 samples. Background sync follows Android scheduling restrictions."
            + " Dark appearance. Measurements are experimental; this is not an emergency service.");
  }

  private void alerts() {
    heading("Device event timeline");
    if (model.mode.equals("BACKEND")) {
      action("Refresh alerts", model::loadAlerts);
      JsonArray values = model.alerts.getValue();
      if (values.size() == 0) paragraph("No alerts received for this device.");
      for (var value : values) {
        JsonObject a = value.getAsJsonObject();
        paragraph(
            a.get("kind").getAsString().replace('_', ' ')
                + " · "
                + a.get("severity").getAsString()
                + "\n"
                + a.get("source").getAsString()
                + " · "
                + a.get("created_at").getAsString());
        if (a.get("acknowledged_at").isJsonNull())
          action("Acknowledge", () -> model.acknowledge(a.get("id").getAsString()));
        else paragraph("Acknowledged");
      }
    } else {
      boolean any = false;
      for (var row : model.selected()) {
        Telemetry t = model.decode(row);
        if (t.alerts().isEmpty()) continue;
        any = true;
        paragraph(
            time(Instant.ofEpochMilli(row.receivedAt)) + " · " + t.alerts() + " · " + t.source());
        if (!row.reviewed)
          action(
              "Mark reviewed locally",
              () -> model.worker.execute(() -> model.db.readings().review(row.id)));
        else paragraph("Reviewed locally");
      }
      if (!any)
        paragraph(
            "No locally cached BLE alerts. Review is stored on this phone; backend acknowledgement"
                + " is separate.");
    }
  }

  private void scanPermissions() {
    String[] required =
        Build.VERSION.SDK_INT >= 31
            ? new String[] {
              Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            }
            : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    if (Arrays.stream(required)
        .anyMatch(p -> checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED))
      permissions.launch(required);
    else scan();
  }

  private void scan() {
    try {
      var manager = getSystemService(android.bluetooth.BluetoothManager.class);
      var adapter = manager == null ? null : manager.getAdapter();
      if (adapter != null && !adapter.isEnabled()) {
        bluetoothEnable.launch(
            new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
        return;
      }
      model.discovered.setValue(Map.of());
      model.discover();
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  private void chart(boolean oxygen) {
    List<Double> values = new ArrayList<>();
    List<ReadingEntry> rows = new ArrayList<>(model.selected().stream().limit(240).toList());
    Collections.reverse(rows);
    for (var row : rows) {
      Telemetry t = model.decode(row);
      values.add(oxygen ? t.health().spo2Percent() : t.health().heartRateBpm());
    }
    LinearLayout panel = card(oxygen ? "Oxygen estimate · %" : "Heart rate · bpm");
    TextView caption =
        ui.text("Latest " + rows.size() + " samples · gaps mean unavailable", 12, MobileUi.MUTED);
    panel.addView(caption);
    SignalChart chart = new SignalChart(this, values, oxygen ? "SpO₂ %" : "bpm");
    LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, dp(166));
    chartParams.topMargin = dp(12);
    panel.addView(chart, chartParams);
  }

  private void heading(String value) {
    TextView title = ui.text(value, 20, MobileUi.TEXT);
    title.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(8);
    binding.content.addView(title, params);
  }

  private void paragraph(String value) {
    TextView body = ui.text(value, 13, MobileUi.MUTED);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(16);
    binding.content.addView(body, params);
  }

  private LinearLayout card(String title) {
    LinearLayout panel = ui.card();
    TextView label = ui.text(title, 14, MobileUi.TEXT);
    label.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(10);
    panel.addView(label, params);
    binding.content.addView(panel);
    return panel;
  }

  private void metric(String title, String value, String detail) {
    binding.content.addView(ui.metric(title, value, detail));
  }

  private void healthSummary(Telemetry t) {
    LinearLayout pulse =
        ui.metric(
            "HEART RATE",
            num(t.health().heartRateBpm(), 0),
            "bpm · " + t.health().quality().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    LinearLayout oxygen =
        ui.metric("OXYGEN ESTIMATE", num(t.health().spo2Percent(), 1), "% · experimental");
    if (getResources().getConfiguration().screenWidthDp >= 360
        && getResources().getConfiguration().fontScale <= 1.3f) {
      LinearLayout pair = new LinearLayout(this);
      LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -1, 1),
          second = new LinearLayout.LayoutParams(0, -1, 1);
      first.setMarginEnd(dp(12));
      pair.addView(pulse, first);
      pair.addView(oxygen, second);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.bottomMargin = dp(12);
      binding.content.addView(pair, params);
    } else {
      binding.content.addView(pulse);
      binding.content.addView(oxygen);
    }
  }

  private void action(String title, Runnable action) {
    binding.content.addView(ui.button(title, action, false));
  }

  private LinearLayout dialogLayout() {
    LinearLayout result = new LinearLayout(this);
    result.setOrientation(LinearLayout.VERTICAL);
    result.setPadding(dp(24), dp(8), dp(24), dp(8));
    return result;
  }

  private TextInputEditText input(
      LinearLayout parent, String hint, boolean password, String initial) {
    LinearLayout wrapper = new LinearLayout(this);
    wrapper.setOrientation(LinearLayout.VERTICAL);
    TextView label = ui.text(hint, 12, MobileUi.MUTED);
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
    labelParams.bottomMargin = dp(8);
    wrapper.addView(label, labelParams);
    TextInputLayout field = new TextInputLayout(this);
    field.setHintEnabled(false);
    field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    field.setBoxCornerRadii(dp(9), dp(9), dp(9), dp(9));
    field.setBoxBackgroundColor(MobileUi.SURFACE);
    field.setBoxStrokeColor(MobileUi.ACCENT);
    field.setBoxStrokeWidth(dp(1));
    field.setBoxStrokeWidthFocused(dp(1));
    if (hint.equals("Backend address"))
      field.setHelperText(
          "Use the phone URL shown by Start-Backend.cmd (port 8087). 127.0.0.1 points to this"
              + " phone.");
    TextInputEditText edit = new TextInputEditText(this);
    edit.setId(View.generateViewId());
    label.setLabelFor(edit.getId());
    edit.setBackground(null);
    edit.setPadding(dp(12), dp(14), dp(12), dp(14));
    edit.setSingleLine(true);
    edit.setTextSize(14);
    edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
    edit.setText(initial);
    edit.setInputType(
        password
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT
                | (hint.toLowerCase(Locale.ROOT).contains("email")
                    ? InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    : hint.toLowerCase(Locale.ROOT).contains("address")
                        ? InputType.TYPE_TEXT_VARIATION_URI
                        : 0));
    field.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    wrapper.addView(field, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(16);
    parent.addView(wrapper, p);
    return edit;
  }

  private void toast(String value) {
    Toast.makeText(this, value == null ? "Action failed" : value, Toast.LENGTH_LONG).show();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private String time(Instant instant) {
    return DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")
        .withZone(ZoneId.of(model.vault.preferences().getString("timezone", "Asia/Kolkata")))
        .format(instant);
  }

  private static String num(Number value, int decimals) {
    return value == null
        ? "—"
        : String.format(Locale.ROOT, "%." + decimals + "f", value.doubleValue());
  }
}
