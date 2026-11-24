package org.smarttrack.desktop;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.event.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

/** Application inheritance, event dispatch, properties, collections and worker threads. */
public class SmartTrackDesktop extends Application {
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          4,
          r -> {
            var t = new Thread(r, "smarttrack-worker");
            t.setDaemon(true);
            return t;
          });
  private final Preferences prefs = Preferences.userNodeForPackage(getClass());
  private final BooleanProperty connected = new SimpleBooleanProperty(false),
      busy = new SimpleBooleanProperty(false);
  private final ObservableList<String> deviceIds = FXCollections.observableArrayList();
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private boolean backendHealthy;
  private volatile boolean streaming;
  private final Label status = new Label("Disconnected"), freshness = new Label("No reading");
  private final ComboBox<String> device = new ComboBox<>(deviceIds);
  private final BorderPane root = new BorderPane();
  private final VBox content = new VBox(20);
  private ApiClient api;
  private final Label connectionDetails = new Label("Wi-Fi | Shared server history");
  private Stage stage;
  private volatile long generation;
  private String page = "Dashboard";
  private JsonNode latest,
      history = JsonNodeFactory.instance.arrayNode(),
      alerts = JsonNodeFactory.instance.arrayNode(),
      devices = JsonNodeFactory.instance.arrayNode();
  private Timeline poll;
  private Timeline ageClock;
  private int historyLimit = 120, refreshSeconds = 3;
  private boolean alertPopups = true, light = false;
  private final Set<Long> seenAlerts = new HashSet<>();

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    refreshSeconds = prefs.getInt("refresh", 3);
    light = prefs.getBoolean("light", false);
    alertPopups = prefs.getBoolean("alerts", true);
    Scene scene = new Scene(root, 1280, 840);
    scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
    stage.setTitle("SmartTrack • Wearable intelligence");
    stage.setScene(scene);
    stage.setMinWidth(920);
    stage.setMinHeight(650);
    stage.setOnCloseRequest(e -> stop());
    login();
    stage.show();
  }

  private Label title(String text) {
    Label l = new Label(text);
    l.getStyleClass().add("title");
    return l;
  }

  private Label muted(String text) {
    Label l = new Label(text);
    l.getStyleClass().add("muted");
    l.setWrapText(true);
    return l;
  }

  private Button button(String text, EventHandler<ActionEvent> handler) {
    var b = new Button(text);
    b.setOnAction(handler);
    return b;
  }

  private void login() {
    root.setLeft(null);
    root.setTop(null);
    root.setBottom(null);
    VBox box = new VBox(18);
    box.setMaxWidth(460);
    box.getStyleClass().add("card");
    TextField url = new TextField(prefs.get("backend", "http://192.168.137.1:8080")),
        user = new TextField(prefs.get("username", ""));
    url.setEditable(true);
    PasswordField password = new PasswordField();
    password.setPromptText("Password");
    url.setId("backend");
    user.setId("username");
    password.setId("password");
    Label error = muted("Use the user provisioned on your local backend.");
    Button submit =
        button(
            "Sign in",
            e -> {
              ApiClient candidate;
              try {
                candidate = new ApiClient(url.getText(), user.getText(), password.getText());
              } catch (Exception ex) {
                error.setText(ex.getMessage());
                return;
              }
              async(
                  () -> candidate.request("GET", "/auth/me", null),
                  r -> {
                    api = candidate;
                    generation++;
                    connected.set(true);
                    prefs.put("backend", url.getText());
                    prefs.put("username", user.getText());
                    password.clear();
                    shell();
                    startUpdates();
                  },
                  ex -> {
                    candidate.close();
                    error.setText(ex.getMessage());
                  });
            });
    submit.setId("signin");
    submit.disableProperty().bind(busy);
    password.setOnAction(e -> submit.fire());
    box.getChildren()
        .addAll(
            title("SmartTrack"),
            muted("WI-FI WATCH • LOCAL DATABASE"),
            new Label("Local archive on this laptop"),
            url,
            new Label("Username"),
            user,
            new Label("Password"),
            password,
            submit,
            error,
            muted("Educational prototype • not a medical or emergency device"));
    StackPane center = new StackPane(box);
    center.setPadding(new Insets(30));
    root.setCenter(center);
  }

  private void shell() {
    root.getStyleClass().remove("light");
    if (light) root.getStyleClass().add("light");
    VBox nav = new VBox(10);
    nav.setPadding(new Insets(28, 18, 24, 18));
    nav.setPrefWidth(218);
    nav.getStyleClass().add("sidebar");
    nav.getChildren().addAll(title("SmartTrack"), muted("WEARABLE INTELLIGENCE"), new Separator());
    for (String item :
        List.of(
            "Dashboard",
            "Live Health",
            "Temperature",
            "Activity",
            "Alerts",
            "Devices",
            "Settings")) {
      Button b =
          button(
              item,
              e -> {
                page = item;
                render();
              });
      b.setMaxWidth(Double.MAX_VALUE);
      nav.getChildren().add(b);
    }
    Region space = new Region();
    VBox.setVgrow(space, Priority.ALWAYS);
    nav.getChildren()
        .addAll(
            space,
            muted("WI-FI\nShared history"),
            button(
                "Logout",
                e -> {
                  disconnect();
                  api.close();
                  api = null;
                  deviceIds.clear();
                  device.setValue(null);
                  latest = null;
                  history = JsonNodeFactory.instance.arrayNode();
                  alerts = JsonNodeFactory.instance.arrayNode();
                  devices = JsonNodeFactory.instance.arrayNode();
                  seenAlerts.clear();
                  login();
                }));
    root.setLeft(nav);
    device.setPromptText("Select a device");
    device.setPrefWidth(240);
    device
        .valueProperty()
        .addListener(
            (o, old, value) -> {
              if (!Objects.equals(old, value)) {
                latest = null;
                history = JsonNodeFactory.instance.arrayNode();
                alerts = JsonNodeFactory.instance.arrayNode();
                render();
                refresh();
              }
            });
    Button refresh = button("Refresh", e -> refresh());
    refresh.disableProperty().bind(busy.or(connected.not()));
    Button toggle = new Button();
    toggle
        .textProperty()
        .bind(
            javafx.beans.binding.Bindings.when(connected).then("Disconnect").otherwise("Connect"));
    toggle.setOnAction(
        e -> {
          if (connected.get()) disconnect();
          else {
            connected.set(true);
            generation++;
            startUpdates();
          }
        });
    HBox toolbar = new HBox(14, device, refresh, toggle, status);
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.setPadding(new Insets(20));
    root.setTop(toolbar);
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    content.setPadding(new Insets(22, 30, 30, 30));
    root.setCenter(scroll);
    connectionDetails.setText("Wi-Fi | " + api.baseUrl());
    connectionDetails.setWrapText(true);
    VBox footer = new VBox(5, freshness, connectionDetails);
    footer.setPadding(new Insets(12, 24, 12, 24));
    root.setBottom(footer);
    BorderPane.setMargin(freshness, new Insets(12, 24, 12, 24));
    render();
  }

  private void startUpdates() {
    streaming = true;
    api.resume();
    if (ageClock != null) ageClock.stop();
    ageClock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateFreshness()));
    ageClock.setCycleCount(Timeline.INDEFINITE);
    ageClock.play();
    if (poll != null) poll.stop();
    poll =
        new Timeline(
            new KeyFrame(
                Duration.seconds(refreshSeconds),
                e -> {
                  updateFreshness();
                  refresh();
                }));
    poll.setCycleCount(Timeline.INDEFINITE);
    poll.play();
    refresh();
    final long token = generation;
    final ApiClient client = api;
    workers.submit(
        () -> {
          while (streaming && generation == token && !Thread.currentThread().isInterrupted()) {
            try {
              client.listen(
                  () ->
                      Platform.runLater(
                          () -> {
                            if (generation == token) refresh();
                          }));
            } catch (Exception ignored) {
            }
            try {
              Thread.sleep(1500);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        });
  }

  private void disconnect() {
    streaming = false;
    backendHealthy = false;
    connected.set(false);
    generation++;
    if (poll != null) poll.stop();
    if (ageClock != null) ageClock.stop();
    if (api != null) api.pause();
    status.setText("Disconnected • values are historical");
    freshness.setText("DISCONNECTED • Historical values only");
  }

  private <T> void async(Callable<T> work, Consumer<T> success, Consumer<Exception> error) {
    busy.set(true);
    Task<T> task =
        new Task<>() {
          protected T call() throws Exception {
            return work.call();
          }
        };
    task.setOnSucceeded(
        e -> {
          busy.set(false);
          success.accept(task.getValue());
        });
    task.setOnFailed(
        e -> {
          busy.set(false);
          error.accept(
              task.getException() instanceof Exception ex
                  ? ex
                  : new Exception(task.getException()));
        });
    workers.submit(task);
  }

  private void refresh() {
    if (api == null || !connected.get() || !refreshing.compareAndSet(false, true)) return;
    final long token = generation;
    final String selected = device.getValue();
    final ApiClient client = api;
    final int limit = historyLimit;
    async(
        () -> {
          var ds = client.request("GET", "/devices", null);
          JsonNode h = JsonNodeFactory.instance.arrayNode(),
              a = JsonNodeFactory.instance.arrayNode();
          if (selected != null) {
            h = client.request("GET", "/devices/" + selected + "/history?limit=" + limit, null);
            a = client.request("GET", "/devices/" + selected + "/alerts", null);
          }
          return new JsonNode[] {ds, h, a};
        },
        result -> {
          refreshing.set(false);
          if (token != generation || !Objects.equals(selected, device.getValue())) return;
          devices = result[0];
          List<String> ids = new ArrayList<>();
          devices.forEach(d -> ids.add(d.path("deviceId").asText()));
          if (!deviceIds.equals(ids)) deviceIds.setAll(ids);
          if (device.getValue() == null && !ids.isEmpty()) {
            device.setValue(ids.getFirst());
            return;
          }
          history = result[1];
          alerts = result[2];
          latest = history.isEmpty() ? null : history.get(0);
          backendHealthy = true;
          status.setText("Wi-Fi server connected | " + api.diagnostics());
          updateFreshness();
          notifyAlerts();
          if (!page.equals("Settings") && !page.equals("Devices")) render();
        },
        ex -> {
          refreshing.set(false);
          if (token == generation) {
            backendHealthy = false;
            status.setText("Connection error: " + ex.getMessage());
            freshness.setText("BACKEND UNREACHABLE • Historical values only");
          }
        });
  }

  private void updateFreshness() {
    if (latest == null) {
      freshness.setText("No readings • register hardware or explicitly enable backend mock mode");
      return;
    }
    freshness.setText(
        (latest.path("simulated").asBoolean() ? "SIMULATED • " : "")
            + (!connected.get() || !backendHealthy || ReadingState.stale(latest, Instant.now())
                ? "STALE / HISTORICAL"
                : latest.path("simulated").asBoolean() ? "STREAMING" : "LIVE")
            + " • Received "
            + latest.path("receivedAt").asText());
  }

  private void notifyAlerts() {
    for (var a : alerts)
      if (a.path("acknowledgedAt").isNull()
          && seenAlerts.add(a.path("id").asLong())
          && alertPopups) {
        status.setText("ALERT • " + a.path("message").asText());
      }
  }

  private void render() {
    if (root.getLeft() != null)
      for (Node node : root.getLeft().lookupAll(".button")) {
        Button navigation = (Button) node;
        navigation.getStyleClass().remove("nav-active");
        if (navigation.getText().equals(page)) navigation.getStyleClass().add("nav-active");
      }
    content.getChildren().clear();
    content
        .getChildren()
        .addAll(
            title(page),
            muted("Monitor real Wi-Fi readings and review history stored on this laptop."));
    switch (page) {
      case "Settings" -> settings();
      case "Devices" -> devicesPage();
      case "Alerts" -> alertPage();
      case "Temperature" -> temperaturePage();
      case "Activity" -> activityPage();
      case "Live Health" -> healthPage();
      default -> dashboard();
    }
  }

  private JsonNode part(String field) {
    return latest == null ? MissingNode.getInstance() : latest.path(field);
  }

  private VBox card(String label, String value, String hint) {
    VBox box = new VBox(12, new Label(label), title(value), muted(hint));
    box.getStyleClass().add("card");
    box.setMinWidth(195);
    HBox.setHgrow(box, Priority.ALWAYS);
    return box;
  }

  private void dashboard() {
    var h = part("health");
    var t = part("temperature");
    var m = part("motion");
    FlowPane cards = new FlowPane(16, 16);
    cards
        .getChildren()
        .addAll(
            card(
                "HEART RATE",
                ReadingState.value(h, "bpm", " bpm"),
                h.path("quality").asText("No signal")),
            card("ESTIMATED SpO₂", ReadingState.value(h, "spo2", " %"), "Educational estimate"),
            card(
                "CHIP TEMPERATURE",
                ReadingState.value(t, "celsius", " C"),
                t.path("quality").asText("UNAVAILABLE") + " | LSM6DS3 internal"),
            card(
                "ACTIVITY",
                m.path("activity").asText("Unavailable"),
                "Experimental movement classification"));
    content.getChildren().add(cards);
    content.getChildren().add(title("Health trend"));
    content.getChildren().add(chart("bpm", "Heart rate (bpm)"));
    content.getChildren().add(title("Recent alerts"));
    alertRows(4);
  }

  private LineChart<Number, Number> chart(String field, String label) {
    NumberAxis x = new NumberAxis(), y = new NumberAxis();
    x.setLabel("Sample index • oldest → newest");
    y.setLabel(label);
    LineChart<Number, Number> chart = new LineChart<>(x, y);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setPrefHeight(245);
    var series = new XYChart.Series<Number, Number>();
    int n = 0;
    for (int i = history.size() - 1; i >= 0; i--) {
      var h = history.get(i).path(field.equals("celsius") ? "temperature" : "health");
      if (h.path(field).isNumber())
        series
            .getData()
            .add(
                new XYChart.Data<>(
                    n,
                    field.equals("celsius")
                        ? convertTemperature(h.path(field).asDouble())
                        : h.path(field).asDouble()));
      else if (!series.getData().isEmpty()) {
        chart.getData().add(series);
        series = new XYChart.Series<>();
      }
      n++;
    }
    if (!series.getData().isEmpty()) chart.getData().add(series);
    return chart;
  }

  private void healthPage() {
    ComboBox<Integer> filter =
        new ComboBox<>(FXCollections.observableArrayList(60, 120, 500, 1000));
    filter.setValue(historyLimit);
    filter
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              historyLimit = b;
              refresh();
            });
    content
        .getChildren()
        .add(
            new HBox(
                16, new Label("History samples"), filter, button("Export CSV", e -> export())));
    content
        .getChildren()
        .addAll(
            muted(
                "Signal: "
                    + part("health").path("quality").asText("Unavailable")
                    + " • Missing values appear as gaps."),
            chart("bpm", "Heart rate (bpm)"),
            chart("spo2", "Estimated SpO₂ (%)"));
    ListView<String> list = new ListView<>();
    list.setPrefHeight(180);
    for (var row : history)
      list.getItems()
          .add(
              row.path("receivedAt").asText()
                  + "   "
                  + ReadingState.value(row.path("health"), "bpm", " bpm")
                  + "   "
                  + ReadingState.value(row.path("health"), "spo2", " %")
                  + "   "
                  + row.path("health").path("quality").asText());
    list.setPlaceholder(new Label("No readings yet"));
    content.getChildren().add(list);
  }

  private boolean fahrenheit;

  private void temperaturePage() {
    var t = part("temperature");
    String value =
        t.path("celsius").isNumber()
            ? String.format(
                Locale.ROOT,
                "%.1f %s",
                convertTemperature(t.path("celsius").asDouble()),
                fahrenheit ? "F" : "C")
            : "Unavailable";
    content
        .getChildren()
        .add(
            card(
                "INTERNAL CHIP TEMPERATURE",
                value,
                t.path("quality").asText("UNAVAILABLE")
                    + " | Sample age: "
                    + t.path("sampleAgeMs").asText("--")
                    + " ms"));
    content
        .getChildren()
        .add(
            muted(
                "LSM6DS3 die temperature. Board heat and self-heating affect this reading. It is"
                    + " not body temperature."));
    Button units = new Button(fahrenheit ? "Show Celsius" : "Show Fahrenheit");
    units.setOnAction(
        e -> {
          fahrenheit = !fahrenheit;
          render();
        });
    content.getChildren().add(units);
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    int count = 0;
    for (var r : history) {
      var v = r.path("temperature");
      if ("GOOD".equals(v.path("quality").asText()) && v.path("celsius").isNumber()) {
        double c = convertTemperature(v.path("celsius").asDouble());
        min = Math.min(min, c);
        max = Math.max(max, c);
        count++;
      }
    }
    content
        .getChildren()
        .add(
            card(
                "DISPLAYED HISTORY WINDOW",
                count == 0
                    ? "No valid samples"
                    : String.format(
                        Locale.ROOT, "Min %.1f | Max %.1f %s", min, max, fahrenheit ? "F" : "C"),
                count + " valid samples; missing readings leave chart gaps"));
    content
        .getChildren()
        .add(chart("celsius", "Chip temperature (" + (fahrenheit ? "F" : "C") + ")"));
    ListView<String> rows = new ListView<>();
    rows.setPrefHeight(210);
    for (var r : history) {
      var v = r.path("temperature");
      rows.getItems()
          .add(
              r.path("receivedAt").asText()
                  + " | "
                  + (v.path("celsius").isNumber()
                      ? String.format(
                          Locale.ROOT,
                          "%.1f %s",
                          convertTemperature(v.path("celsius").asDouble()),
                          fahrenheit ? "F" : "C")
                      : "--")
                  + " | "
                  + v.path("quality").asText("UNAVAILABLE"));
    }
    rows.setPlaceholder(new Label("No temperature history yet"));
    content.getChildren().add(rows);
  }

  private double convertTemperature(double c) {
    return fahrenheit ? c * 1.8 + 32 : c;
  }

  private void activityPage() {
    var m = part("motion");
    var vm = new MotionViewModel();
    vm.update(m, history);
    Label quality = new Label();
    quality.textProperty().bind(vm.qualityProperty());
    content
        .getChildren()
        .addAll(
            quality,
            card(
                "LSM6DS3 MOTION",
                m.path("activity").asText("UNAVAILABLE"),
                "Calibration: " + m.path("calibration").asText("UNVERIFIED")),
            card(
                "FALL STATE",
                m.path("fallState").asText("NORMAL"),
                "Experimental algorithm • Event " + m.path("eventId").asText("—")));
    FlowPane grid = new FlowPane(14, 14);
    for (String f : List.of("ax", "ay", "az", "gx", "gy", "gz", "pitch", "roll", "magnitude"))
      grid.getChildren()
          .add(
              card(
                  f.toUpperCase(),
                  vm.reading(f),
                  Set.of("ax", "ay", "az", "magnitude").contains(f)
                      ? vm.accelerationUnit()
                      : f.equals("temperature")
                          ? "°C"
                          : f.startsWith("g") ? "degrees/s" : "degrees"));
    content.getChildren().add(grid);
    content.getChildren().add(new Label("Inactivity: " + m.path("inactive").asBoolean()));
    var x = new NumberAxis();
    var y = new NumberAxis();
    x.setLabel("Sample • oldest → newest");
    y.setLabel("Acceleration magnitude (g)");
    var chart = new LineChart<Number, Number>(x, y);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setPrefHeight(260);
    var series = new XYChart.Series<Number, Number>();
    series.setData(vm.magnitude());
    chart.getData().add(series);
    content.getChildren().add(chart);
    ListView<String> impacts = new ListView<>(vm.impacts());
    impacts.setPrefHeight(180);
    impacts.setPlaceholder(new Label("No impacts in this history window"));
    content.getChildren().addAll(title("Impact history"), impacts);
    content
        .getChildren()
        .add(
            new HBox(
                12,
                button("Cancel fall event", e -> deviceCommand("cancel")),
                button("Calibrate flat +Z up", e -> deviceCommand("calibrate"))));
  }

  private void deviceCommand(String command) {
    if (device.getValue() == null || !connected.get()) return;
    String id = device.getValue();
    async(
        () -> api.request("POST", "/devices/" + id + "/commands/" + command, null),
        r -> status.setText("Command queued for the watch; await its next configuration update."),
        this::showError);
  }

  private void motionSettings() {
    String id = device.getValue();
    if (id == null) return;
    TextField sensitivity = new TextField("1.0"),
        impact = new TextField("2.5"),
        low = new TextField("0.6"),
        inactive = new TextField("2000"),
        cooldown = new TextField("20000");
    content
        .getChildren()
        .addAll(
            title("LSM6DS3 sensitivity"),
            new Label("Sensitivity (0.5–2)"),
            sensitivity,
            new Label("Impact threshold (g, 2–6)"),
            impact,
            new Label("Low-g threshold (0.2–0.8)"),
            low,
            new Label("Post-impact inactivity (ms)"),
            inactive,
            new Label("Recovery cooldown (ms)"),
            cooldown,
            button(
                "Save motion configuration",
                e -> {
                  try {
                    var values =
                        Map.of(
                            "sensitivity",
                            Double.parseDouble(sensitivity.getText()),
                            "impactG",
                            Double.parseDouble(impact.getText()),
                            "lowG",
                            Double.parseDouble(low.getText()),
                            "inactivityMs",
                            Long.parseLong(inactive.getText()),
                            "cooldownMs",
                            Long.parseLong(cooldown.getText()));
                    async(
                        () -> api.request("PUT", "/devices/" + id + "/settings", values),
                        r ->
                            status.setText(
                                "Motion settings saved; the watch will fetch them over Wi-Fi."),
                        this::showError);
                  } catch (Exception ex) {
                    showError(ex);
                  }
                }));
    async(
        () -> api.request("GET", "/devices/" + id + "/settings", null),
        r -> {
          sensitivity.setText(r.path("sensitivity").asText());
          impact.setText(r.path("impactG").asText());
          low.setText(r.path("lowG").asText());
          inactive.setText(r.path("inactivityMs").asText());
          cooldown.setText(r.path("cooldownMs").asText());
        },
        this::showError);
  }

  private void alertPage() {
    alertRows(200);
  }

  private void alertRows(int limit) {
    if (alerts.isEmpty()) {
      content.getChildren().add(muted("No alerts in this device's history."));
      return;
    }
    int n = 0;
    for (var a : alerts) {
      if (n++ >= limit) break;
      boolean ack = !a.path("acknowledgedAt").isNull();
      Label message = new Label(a.path("severity").asText() + " • " + a.path("message").asText());
      message.setWrapText(true);
      VBox text =
          new VBox(
              8,
              message,
              muted(a.path("createdAt").asText() + (ack ? " • Acknowledged" : " • Active")));
      HBox.setHgrow(text, Priority.ALWAYS);
      Button acknowledge =
          button(
              "Acknowledge",
              e -> {
                String id = device.getValue();
                async(
                    () ->
                        api.request(
                            "POST",
                            "/devices/" + id + "/alerts/" + a.path("id").asLong() + "/ack",
                            null),
                    r -> refresh(),
                    this::showError);
              });
      acknowledge.setDisable(ack || !connected.get());
      HBox row = new HBox(20, text, acknowledge);
      row.getStyleClass().add("card");
      content.getChildren().add(row);
    }
  }

  private void devicesPage() {
    content
        .getChildren()
        .addAll(
            title("Wi-Fi connection"),
            muted(
                "ESP32 sends readings to the backend. Phone and desktop share the same readings,"
                    + " alerts and history."),
            card("SERVER", api.baseUrl(), backendHealthy ? "Connected" : "Unavailable"),
            card(
                "PHONE SERVER URL",
                System.getenv().getOrDefault("SMARTTRACK_PHONE_URL", "Open SHOW_LOGIN.cmd"),
                "Connect the phone and laptop to the same Wi-Fi; use the same account on both"
                    + " apps."),
            button("Refresh devices", event -> refresh()),
            title("Wearables"));
    for (var d : devices)
      content
          .getChildren()
          .add(
              card(
                  d.path("name").asText(),
                  d.path("deviceId").asText(),
                  (d.path("online").asBoolean() ? "Receiving over Wi-Fi" : "Offline / historical")
                      + " | Last seen "
                      + d.path("lastSeen").asText("Never")));
    content
        .getChildren()
        .add(
            muted(
                "Run CONFIGURE_WIFI.cmd to change the watch network, then UPLOAD_ESP32.cmd. The"
                    + " watch discovers the local server automatically when START_SMARTTRACK.cmd is"
                    + " running."));
  }

  private void settings() {
    motionSettings();
    TextField backend = new TextField(api.baseUrl());
    backend.setEditable(false);
    Spinner<Integer> interval = new Spinner<>(1, 30, refreshSeconds);
    CheckBox popups = new CheckBox("Show alert status notifications");
    popups.setSelected(alertPopups);
    CheckBox theme = new CheckBox("Light theme");
    theme.setSelected(light);
    content
        .getChildren()
        .addAll(
            new Label("Local archive • Phone and desktop share the server history"),
            backend,
            new Label("Refresh interval (seconds)"),
            interval,
            popups,
            theme,
            button(
                "Save settings",
                e -> {
                  try {
                    new java.net.URI(backend.getText());
                    refreshSeconds = interval.getValue();
                    alertPopups = popups.isSelected();
                    light = theme.isSelected();
                    prefs.put("backend", backend.getText());
                    prefs.putInt("refresh", refreshSeconds);
                    prefs.putBoolean("alerts", alertPopups);
                    prefs.putBoolean("light", light);
                    root.getStyleClass().remove("light");
                    if (light) root.getStyleClass().add("light");
                    if (poll != null) {
                      poll.stop();
                      poll.getKeyFrames()
                          .setAll(
                              new KeyFrame(
                                  Duration.seconds(refreshSeconds),
                                  ev -> {
                                    updateFreshness();
                                    refresh();
                                  }));
                      poll.play();
                    }
                    status.setText("Settings saved. Sign in again to change backend.");
                  } catch (Exception ex) {
                    showError(ex);
                  }
                }));
    if (device.getValue() != null) {
      JsonNode d = null;
      for (var row : devices) if (row.path("deviceId").asText().equals(device.getValue())) d = row;
      if (d != null) {
        TextField min = new TextField(d.path("minBpm").asText()),
            max = new TextField(d.path("maxBpm").asText()),
            spo = new TextField(d.path("minSpo2").asText());
        content
            .getChildren()
            .addAll(
                title("Device alert thresholds"),
                new Label("Minimum BPM"),
                min,
                new Label("Maximum BPM"),
                max,
                new Label("Minimum estimated SpO₂"),
                spo,
                button(
                    "Update thresholds",
                    e -> {
                      try {
                        Map<String, Double> values =
                            Map.of(
                                "minBpm",
                                Double.parseDouble(min.getText()),
                                "maxBpm",
                                Double.parseDouble(max.getText()),
                                "minSpo2",
                                Double.parseDouble(spo.getText()));
                        async(
                            () ->
                                api.request(
                                    "PUT", "/devices/" + device.getValue() + "/thresholds", values),
                            r -> {
                              status.setText("Thresholds updated");
                              refresh();
                            },
                            this::showError);
                      } catch (NumberFormatException ex) {
                        showError(new Exception("Enter numeric thresholds"));
                      }
                    }));
      }
    }
  }

  private void export() {
    FileChooser chooser = new FileChooser();
    chooser.setInitialFileName("smarttrack-history.csv");
    var file = chooser.showSaveDialog(stage);
    if (file == null) return;
    JsonNode snapshot = history.deepCopy();
    async(
        () -> {
          StringBuilder csv =
              new StringBuilder(
                  "receivedAt,simulated,bpm,spo2,quality,chip_temperature_c,temperature_quality\n");
          for (var r : snapshot)
            csv.append(r.path("receivedAt").asText())
                .append(',')
                .append(r.path("simulated").asBoolean())
                .append(',')
                .append(r.path("health").path("bpm").asText(""))
                .append(',')
                .append(r.path("health").path("spo2").asText(""))
                .append(',')
                .append(r.path("health").path("quality").asText())
                .append(',')
                .append(r.path("temperature").path("celsius").asText(""))
                .append(',')
                .append(r.path("temperature").path("quality").asText(""))
                .append('\n');
          Files.writeString(file.toPath(), csv.toString());
          return file;
        },
        r -> status.setText("Exported " + r.getName()),
        this::showError);
  }

  private void showError(Exception ex) {
    new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK).show();
  }

  @Override
  public void stop() {
    disconnect();
    if (api != null) api.close();
    workers.shutdownNow();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
