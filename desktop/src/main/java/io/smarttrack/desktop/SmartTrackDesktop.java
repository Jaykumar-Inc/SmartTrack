package io.smarttrack.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import io.smarttrack.contract.BackendEndpoint;
import io.smarttrack.contract.Telemetry;
import java.net.URI;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.prefs.Preferences;
import javafx.animation.*;
import javafx.application.*;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

public class SmartTrackDesktop extends Application {
  private final Preferences prefs = Preferences.userNodeForPackage(getClass());
  private ApiClient api;
  private Stage stage;
  private BorderPane root;
  private VBox workspace, sidebar;
  private Label connection = new Label("Not connected"), message = new Label();
  private ComboBox<String> deviceSelect = new ComboBox<>();
  private final Map<String, String> names = new LinkedHashMap<>();
  private String device, page = "Overview";
  private Telemetry latest;
  private Instant received;
  private String lastReadingId = "";
  private boolean connected, busy;
  private final List<JsonNode> history = new ArrayList<>();
  private final List<Runnable> liveUpdates = new ArrayList<>();
  private final Map<String, Button> navigation = new HashMap<>();
  private Timeline ticker;
  private ZoneId zone;
  private int hours = 1;
  private List<JsonNode> alerts = List.of();

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    zone = ZoneId.of(prefs.get("timezone", "Asia/Kolkata"));
    api =
        new ApiClient(
            Optional.ofNullable(System.getenv("SMARTTRACK_BACKEND"))
                .orElse(localBackendPreference()));
    root = new BorderPane();
    Scene scene = new Scene(root, 1280, 840);
    scene.getStylesheets().add(getClass().getResource("/smarttrack.css").toExternalForm());
    stage.setTitle("SmartTrack · Clarity in every signal");
    stage.setMinWidth(1000);
    stage.setMinHeight(700);
    stage.setScene(scene);
    stage.show();
    scene
        .getAccelerators()
        .put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), () -> refresh(true));
    String preset = System.getenv("SMARTTRACK_USER_TOKEN");
    if (preset != null) {
      api.token(preset);
      shell();
    } else login();
  }

  private void login() {
    if (ticker != null) ticker.stop();
    api.disconnectStream();
    var title = label("SmartTrack", "brand");
    var subtitle = label("Clarity in every signal.", "subtitle");
    TextField address = new TextField(api.base()), email = new TextField();
    email.setPromptText("you@example.com");
    PasswordField password = new PasswordField();
    password.setPromptText("At least 12 characters");
    Label info = label("Sign in to your private device workspace.", "muted");
    Button sign = button("Sign in", () -> credentials(false, address, email, password, info)),
        register =
            button("Create account", () -> credentials(true, address, email, password, info));
    Button test = button("Test connection", () -> testConnection(address.getText(), info));
    address.setId("login-backend");
    email.setId("login-email");
    password.setId("login-password");
    info.setId("login-result");
    sign.setId("login-sign-in");
    register.setId("login-register");
    test.setId("login-test");
    sign.getStyleClass().add("primary");
    sign.setDefaultButton(true);
    VBox panel =
        new VBox(
            16,
            title,
            subtitle,
            new Separator(),
            label("Backend address", "caption"),
            address,
            label(
                "On this laptop: http://127.0.0.1:8087. Start Start-Backend.cmd first.", "caption"),
            test,
            label("Email", "caption"),
            email,
            label("Password", "caption"),
            password,
            new HBox(8, sign, register),
            info,
            label(
                "HTTP is supported for this local setup. Use HTTPS for remote access.", "caption"));
    panel.setMaxWidth(400);
    panel.getStyleClass().add("login");
    StackPane frame = new StackPane(panel);
    root.setLeft(null);
    root.setTop(null);
    root.setCenter(frame);
  }

  private void credentials(
      boolean register, TextField address, TextField email, PasswordField password, Label info) {
    try {
      api.setBase(address.getText());
      if (!email.getText().strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
        throw new IllegalArgumentException("Enter a valid email address");
      if (password.getText().length() < 12
          || password.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
        throw new IllegalArgumentException(
            "Use a password of at least 12 characters and at most 72 UTF-8 bytes");
    } catch (Exception e) {
      info.setText(e.getMessage());
      return;
    }
    info.setText("Connecting…");
    var c = Map.of("email", email.getText().strip(), "password", password.getText());
    api.request("POST", register ? "/v1/auth/register" : "/v1/auth/login", c)
        .thenCompose(
            r ->
                register
                    ? api.request("POST", "/v1/auth/login", c)
                    : java.util.concurrent.CompletableFuture.completedFuture(r))
        .whenComplete(
            (r, e) ->
                Platform.runLater(
                    () -> {
                      if (e != null) {
                        info.setText(error(e));
                        return;
                      }
                      api.token(r.path("token").asText());
                      password.clear();
                      prefs.put("backend", api.base());
                      shell();
                    }));
  }

  private String localBackendPreference() {
    String saved = prefs.get("backend", BackendEndpoint.DESKTOP);
    return saved.equals("http://127.0.0.1:8080") || saved.equals("http://localhost:8080")
        ? BackendEndpoint.DESKTOP
        : saved;
  }

  private void testConnection(String address, Label result) {
    try {
      ApiClient probe = new ApiClient(address);
      result.setText("Checking backend...");
      probe
          .request("GET", "/actuator/health", null)
          .whenComplete(
              (value, failure) -> {
                probe.close();
                Platform.runLater(
                    () ->
                        result.setText(
                            failure != null
                                ? error(failure)
                                : value.path("status").asText().equals("UP")
                                    ? "Backend ready. You can sign in or create an account."
                                    : "Backend responded but is not ready."));
              });
    } catch (Exception failure) {
      result.setText(error(failure));
    }
  }

  private void shell() {
    sidebar = new VBox(8);
    sidebar.getStyleClass().add("sidebar");
    sidebar.setPrefWidth(216);
    sidebar
        .getChildren()
        .addAll(
            label("◈  SmartTrack", "brand"),
            label("CONNECTED SIGNALS", "eyebrow"),
            new Separator());
    for (String p :
        List.of(
            "Overview",
            "Health",
            "Motion",
            "Location",
            "Alerts",
            "Devices",
            "Analytics",
            "Settings")) {
      Button b = button(p, () -> navigate(p));
      b.getStyleClass().add("nav");
      b.setMaxWidth(Double.MAX_VALUE);
      navigation.put(p, b);
      sidebar.getChildren().add(b);
    }
    Region spacer = new Region();
    VBox.setVgrow(spacer, Priority.ALWAYS);
    sidebar
        .getChildren()
        .addAll(
            spacer,
            label("Clarity in every signal.", "caption"),
            label("1.0 · Development build", "caption"));
    root.setLeft(sidebar);
    deviceSelect.setPromptText("Select a device");
    deviceSelect.setPrefWidth(260);
    deviceSelect.setOnAction(
        e -> {
          String selected = deviceSelect.getValue();
          if (selected != null && !selected.equals(device)) {
            device = selected;
            prefs.put("device", device);
            latest = null;
            received = null;
            lastReadingId = "";
            history.clear();
            alerts = List.of();
            api.stream(
                device,
                r -> Platform.runLater(() -> accept(r)),
                s -> Platform.runLater(() -> connection.setText(s)));
            refresh(true);
            render();
          }
        });
    Button refresh = button("Refresh  Ctrl+R", () -> refresh(true));
    HBox top =
        new HBox(
            16, label("WORKSPACE", "eyebrow"), deviceSelect, new Region(), connection, refresh);
    HBox.setHgrow(top.getChildren().get(2), Priority.ALWAYS);
    top.getStyleClass().add("topbar");
    top.setAlignment(Pos.CENTER_LEFT);
    root.setTop(top);
    workspace = new VBox(20);
    workspace.setPadding(new Insets(32));
    ScrollPane scroll = new ScrollPane(workspace);
    scroll.setFitToWidth(true);
    root.setCenter(scroll);
    message.setWrapText(true);
    message.getStyleClass().add("notice");
    navigate(page);
    loadDevices();
    ticker =
        new Timeline(
            new KeyFrame(
                Duration.seconds(3),
                e -> {
                  refresh(false);
                  liveUpdates.forEach(Runnable::run);
                }));
    ticker.setCycleCount(Timeline.INDEFINITE);
    ticker.play();
  }

  private void loadDevices() {
    api.request("GET", "/v1/devices", null)
        .whenComplete(
            (r, e) ->
                Platform.runLater(
                    () -> {
                      if (e != null) {
                        message.setText(error(e));
                        connected = false;
                        return;
                      }
                      connected = true;
                      names.clear();
                      r.forEach(d -> names.put(d.path("id").asText(), d.path("name").asText()));
                      deviceSelect.getItems().setAll(names.keySet());
                      String preferred = prefs.get("device", "");
                      if (device != null && names.containsKey(device))
                        deviceSelect.setValue(device);
                      else if (!names.isEmpty())
                        deviceSelect.setValue(
                            names.containsKey(preferred)
                                ? preferred
                                : names.keySet().iterator().next());
                      render();
                    }));
  }

  private void navigate(String p) {
    page = p;
    navigation.forEach(
        (name, b) -> {
          b.getStyleClass().remove("selected");
          if (name.equals(p)) b.getStyleClass().add("selected");
        });
    render();
    if (device != null && (p.equals("Alerts") || p.equals("Analytics") || p.equals("Health")))
      refresh(true);
  }

  private void refresh(boolean includeHistory) {
    if (api.token() == null || device == null || busy) return;
    busy = true;
    String requested = device;
    api.request("GET", "/v1/devices/" + device + "/latest", null)
        .whenComplete(
            (r, e) ->
                Platform.runLater(
                    () -> {
                      busy = false;
                      if (!Objects.equals(requested, device)) return;
                      if (e != null) {
                        connected = false;
                        connection.setText("Backend unavailable or no readings");
                        message.setText(error(e));
                      } else {
                        connected = true;
                        message.setText("");
                        accept(r);
                        if (api.shouldReconnectStream())
                          api.stream(
                              device,
                              v -> Platform.runLater(() -> accept(v)),
                              s -> Platform.runLater(() -> connection.setText(s)));
                      }
                      liveUpdates.forEach(Runnable::run);
                    }));
    if (includeHistory) {
      String path =
          "/v1/devices/"
              + device
              + "/history?from="
              + Instant.now().minusSeconds(hours * 3600L)
              + "&to="
              + Instant.now().plusSeconds(1)
              + "&limit=2000";
      api.request("GET", path, null)
          .whenComplete(
              (r, e) ->
                  Platform.runLater(
                      () -> {
                        if (!Objects.equals(requested, device)) return;
                        if (e != null) {
                          message.setText(error(e));
                          return;
                        }
                        history.clear();
                        r.forEach(history::add);
                        Collections.reverse(history);
                        if (!page.equals("Settings") && !page.equals("Devices")) render();
                      }));
      api.request("GET", "/v1/devices/" + device + "/alerts", null)
          .whenComplete(
              (r, e) ->
                  Platform.runLater(
                      () -> {
                        if (!Objects.equals(requested, device)) return;
                        if (e == null) {
                          alerts = new ArrayList<>();
                          r.forEach(alerts::add);
                          if (page.equals("Alerts") || page.equals("Overview")) render();
                        }
                      }));
    }
  }

  private void accept(JsonNode r) {
    try {
      Telemetry value = api.json.treeToValue(r.get("telemetry"), Telemetry.class);
      value.validate();
      if (!Objects.equals(device, value.deviceId())) return;
      String id = r.path("id").asText();
      if (id.equals(lastReadingId)) return;
      Instant stamp = Instant.parse(r.path("receivedAt").asText());
      if (latest != null) {
        Instant old = latest.timestamp() == null ? received : latest.timestamp();
        Instant next = value.timestamp() == null ? stamp : value.timestamp();
        if (next.isBefore(old)) return;
      }
      latest = value;
      received = stamp;
      lastReadingId = id;
      connected = true;
      history.removeIf(n -> n.path("id").asText().equals(id));
      history.add(r);
      while (history.size() > 2000) history.removeFirst();
      liveUpdates.forEach(Runnable::run);
    } catch (Exception e) {
      message.setText("Telemetry rejected: " + e.getMessage());
    }
  }

  private void render() {
    if (workspace == null) return;
    liveUpdates.clear();
    workspace.getChildren().clear();
    var heading =
        new HBox(
            16,
            label(page, "page-title"),
            new Region(),
            label(
                device == null ? "No device selected" : names.getOrDefault(device, device),
                "muted"));
    heading.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(heading.getChildren().get(1), Priority.ALWAYS);
    workspace.getChildren().addAll(heading, message);
    if (page.equals("Settings")) {
      settings();
      return;
    }
    if (page.equals("Devices")) {
      devices();
      return;
    }
    Label provenance = label("", "signal-state");
    liveUpdates.add(
        () ->
            provenance.setText(
                latest == null
                    ? "AWAITING SIGNAL · no measurements received"
                    : SignalState.describe(latest, received, Instant.now(), connected)));
    workspace.getChildren().add(provenance);
    if (device == null) {
      workspace
          .getChildren()
          .add(
              panel(
                  "Your first signal starts here",
                  label(
                      "Register a device, then provision its token or run the labeled simulator.",
                      "muted"),
                  button("Open devices", () -> navigate("Devices"))));
      liveUpdates.forEach(Runnable::run);
      return;
    }
    switch (page) {
      case "Overview" -> overview();
      case "Health" -> health();
      case "Motion" -> motion();
      case "Location" -> location();
      case "Alerts" -> alerts();
      case "Analytics" -> analytics();
      default -> throw new IllegalStateException(page);
    }
    liveUpdates.forEach(Runnable::run);
  }

  private void overview() {
    workspace
        .getChildren()
        .add(label("A clear view of your device, from pulse to position.", "muted"));
    HBox metrics =
        new HBox(
            16,
            metric(
                "HEART RATE",
                () -> value(latest == null ? null : latest.health().heartRateBpm(), 0),
                "bpm",
                () -> latest == null ? "Awaiting contact" : latest.health().quality().name()),
            metric(
                "OXYGEN ESTIMATE",
                () -> value(latest == null ? null : latest.health().spo2Percent(), 1),
                "%",
                () -> "Experimental · not medically validated"),
            metric(
                "ACCELERATION",
                () -> value(latest == null ? null : latest.motion().magnitudeG(), 2),
                "g",
                () -> latest == null ? "Awaiting motion" : latest.motion().activity().name()));
    metrics.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
    workspace.getChildren().add(metrics);
    workspace
        .getChildren()
        .add(panel("Signal timeline", chart("heartRateBpm", "Heart rate · bpm", "health")));
    HBox lower =
        new HBox(
            16,
            panel(
                "Device connection",
                field(
                    "Transport",
                    () ->
                        latest == null
                            ? "Unavailable"
                            : latest.status().wifiConnected() ? "Wi-Fi" : "Buffered / BLE sync"),
                field(
                    "Signal strength",
                    () -> latest == null ? "—" : value(latest.status().rssiDbm(), 0) + " dBm"),
                field("Firmware", () -> latest == null ? "—" : latest.status().firmwareVersion())),
            panel(
                "Position & events",
                field(
                    "GPS",
                    () ->
                        latest == null
                            ? "Awaiting receiver"
                            : latest.gps().fix()
                                ? "Valid satellite fix"
                                : latest.gps().communicating()
                                    ? "Receiver active · no current fix"
                                    : "No GPS communication"),
                field("Recent alerts", () -> Integer.toString(alerts.size())),
                button("Review alerts", () -> navigate("Alerts"))));
    lower.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
    workspace.getChildren().add(lower);
  }

  private void health() {
    workspace
        .getChildren()
        .addAll(
            label(
                "Experimental physiological estimates. Signal quality determines availability.",
                "muted"),
            field(
                "Measurement state",
                () -> latest == null ? "Awaiting signal" : latest.health().quality().name()),
            range(),
            panel("Heart rate", chart("heartRateBpm", "bpm", "health")),
            panel("Oxygen estimate", chart("spo2Percent", "%", "health")));
  }

  private void motion() {
    workspace
        .getChildren()
        .addAll(
            label("Motion in g and degrees/second. Fall detection is experimental.", "muted"),
            new HBox(
                16,
                metric(
                    "MAGNITUDE",
                    () -> value(latest == null ? null : latest.motion().magnitudeG(), 3),
                    "g",
                    () -> latest == null ? "—" : latest.motion().activity().name()),
                metric(
                    "PITCH / ROLL",
                    () ->
                        latest == null
                            ? "—"
                            : value(latest.motion().pitchDeg(), 1)
                                + " / "
                                + value(latest.motion().rollDeg(), 1),
                    "°",
                    () -> "Gravity referenced")),
            panel(
                "Three-axis motion",
                field(
                    "Acceleration X / Y / Z",
                    () ->
                        latest == null
                            ? "—"
                            : triple(
                                    latest.motion().axG(),
                                    latest.motion().ayG(),
                                    latest.motion().azG())
                                + " g"),
                field(
                    "Angular velocity X / Y / Z",
                    () ->
                        latest == null
                            ? "—"
                            : triple(
                                    latest.motion().gxDps(),
                                    latest.motion().gyDps(),
                                    latest.motion().gzDps())
                                + " °/s"),
                field(
                    "Temperature",
                    () -> latest == null ? "—" : value(latest.motion().temperatureC(), 1) + " °C"),
                field(
                    "Fall detector",
                    () -> latest == null ? "Unavailable" : latest.motion().fallState().name())),
            panel("Acceleration history", chart("magnitudeG", "g", "motion")));
  }

  private void location() {
    Button map =
        button(
            "Open location in OpenStreetMap",
            () -> {
              if (latest != null && latest.gps().fix())
                getHostServices()
                    .showDocument(
                        "https://www.openstreetmap.org/?mlat="
                            + latest.gps().latitude()
                            + "&mlon="
                            + latest.gps().longitude()
                            + "#map=16/"
                            + latest.gps().latitude()
                            + "/"
                            + latest.gps().longitude());
            });
    liveUpdates.add(() -> map.setDisable(latest == null || !latest.gps().fix()));
    workspace
        .getChildren()
        .addAll(
            panel(
                "Satellite position",
                field(
                    "Receiver",
                    () ->
                        latest == null
                            ? "Unavailable"
                            : latest.gps().communicating()
                                ? "Communicating"
                                : "No NMEA characters"),
                field(
                    "Current fix",
                    () ->
                        latest == null
                            ? "Unavailable"
                            : latest.gps().fix() ? "Valid" : "No fix / stale"),
                field(
                    "Latitude / longitude",
                    () ->
                        latest == null || !latest.gps().fix()
                            ? "Unavailable"
                            : value(latest.gps().latitude(), 6)
                                + ", "
                                + value(latest.gps().longitude(), 6)),
                field(
                    "Satellites", () -> latest == null ? "—" : value(latest.gps().satellites(), 0)),
                field(
                    "Speed / altitude",
                    () ->
                        latest == null
                            ? "—"
                            : value(latest.gps().speedKmh(), 1)
                                + " km/h / "
                                + value(latest.gps().altitudeM(), 1)
                                + " m"),
                field(
                    "Last valid fix",
                    () ->
                        latest == null || latest.gps().lastFixAt() == null
                            ? "Never"
                            : time(latest.gps().lastFixAt())),
                map),
            panel(
                "Location history",
                table(
                    history.stream()
                        .filter(r -> r.path("telemetry").path("gps").path("fix").asBoolean())
                        .toList(),
                    List.of("Received", "Latitude", "Longitude", "Source"),
                    r ->
                        List.of(
                            time(Instant.parse(r.path("receivedAt").asText())),
                            r.at("/telemetry/gps/latitude").asText(),
                            r.at("/telemetry/gps/longitude").asText(),
                            r.at("/telemetry/source").asText()))));
  }

  private void alerts() {
    if (alerts.isEmpty()) {
      workspace
          .getChildren()
          .add(
              panel(
                  "No alerts in this device timeline",
                  label(
                      "New device events appear here. Acknowledgement records review; it does not"
                          + " clear the detector.",
                      "muted")));
      return;
    }
    for (JsonNode a : alerts) {
      boolean ack =
          !a.path("acknowledged_at").isNull() && !a.path("acknowledged_at").isMissingNode();
      Button action =
          button(
              ack ? "Acknowledged" : "Acknowledge",
              () ->
                  api.request(
                          "POST", "/v1/alerts/" + a.path("id").asText() + "/acknowledgement", null)
                      .whenComplete(
                          (r, e) ->
                              Platform.runLater(
                                  () -> {
                                    if (e != null) message.setText(error(e));
                                    else refresh(true);
                                  })));
      action.setDisable(ack);
      workspace
          .getChildren()
          .add(
              panel(
                  a.path("kind").asText().replace('_', ' '),
                  label(
                      a.path("severity").asText()
                          + " · "
                          + a.path("source").asText()
                          + " · "
                          + a.path("created_at").asText(),
                      "muted"),
                  action));
    }
  }

  private void devices() {
    workspace.getChildren().add(button("Register device", this::registerDevice));
    if (names.isEmpty())
      workspace
          .getChildren()
          .add(
              panel(
                  "No registered devices",
                  label(
                      "Create a device identity to receive telemetry. Keep its generated token"
                          + " private.",
                      "muted")));
    names.forEach(
        (id, name) ->
            workspace
                .getChildren()
                .add(
                    panel(
                        name,
                        label(id, "muted"),
                        new HBox(
                            8,
                            button(
                                "Select device",
                                () -> {
                                  deviceSelect.setValue(id);
                                  navigate("Overview");
                                }),
                            button("Device settings", () -> configureDevice(id)),
                            button(
                                "Rotate device token",
                                () -> {
                                  Alert confirm =
                                      new Alert(
                                          Alert.AlertType.CONFIRMATION,
                                          "The previous token will stop working. Reprovision the"
                                              + " device after rotation.",
                                          ButtonType.OK,
                                          ButtonType.CANCEL);
                                  confirm.initOwner(stage);
                                  if (confirm.showAndWait().orElse(ButtonType.CANCEL)
                                      == ButtonType.OK)
                                    api.request("POST", "/v1/devices/" + id + "/rotate-token", null)
                                        .whenComplete(
                                            (r, e) ->
                                                Platform.runLater(
                                                    () -> {
                                                      if (e != null) message.setText(error(e));
                                                      else
                                                        showSecret(r.path("deviceToken").asText());
                                                    }));
                                })))));
  }

  private void registerDevice() {
    TextField id = new TextField(), name = new TextField();
    id.setPromptText("esp32-001");
    name.setPromptText("Device name");
    Dialog<ButtonType> d = new Dialog<>();
    d.initOwner(stage);
    d.setTitle("Register device");
    d.getDialogPane().setContent(new VBox(8, new Label("Device ID"), id, new Label("Name"), name));
    d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
      api.request(
              "POST",
              "/v1/devices",
              Map.of("id", id.getText().strip(), "name", name.getText().strip()))
          .whenComplete(
              (r, e) ->
                  Platform.runLater(
                      () -> {
                        if (e != null) message.setText(error(e));
                        else {
                          showSecret(r.path("deviceToken").asText());
                          loadDevices();
                        }
                      }));
  }

  private void showSecret(String secret) {
    TextArea text = new TextArea(secret);
    text.setEditable(false);
    text.setWrapText(true);
    Alert a = new Alert(Alert.AlertType.INFORMATION);
    a.initOwner(stage);
    a.setTitle("Device token · shown once");
    a.setHeaderText("Store this token securely and provision the device.");
    a.getDialogPane().setContent(text);
    a.showAndWait();
  }

  private void configureDevice(String id) {
    api.request("GET", "/v1/devices/" + id + "/configuration", null)
        .whenComplete(
            (r, e) ->
                Platform.runLater(
                    () -> {
                      if (e != null) {
                        message.setText(error(e));
                        return;
                      }
                      TextField interval = new TextField(r.path("telemetry_interval_ms").asText()),
                          tz = new TextField(r.path("timezone").asText());
                      Dialog<ButtonType> d = new Dialog<>();
                      d.initOwner(stage);
                      d.setTitle("Device configuration");
                      d.getDialogPane()
                          .setContent(
                              new VBox(
                                  8,
                                  new Label("Delivery interval (1000–60000 ms)"),
                                  interval,
                                  new Label("Timezone"),
                                  tz,
                                  new Label(
                                      "Firmware fetches this configuration while connected.")));
                      d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                      if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
                        try {
                          api.request(
                                  "PUT",
                                  "/v1/devices/" + id + "/configuration",
                                  Map.of(
                                      "revision",
                                      r.path("revision").asLong(),
                                      "telemetryIntervalMs",
                                      Integer.parseInt(interval.getText()),
                                      "timezone",
                                      tz.getText()))
                              .whenComplete(
                                  (v, x) ->
                                      Platform.runLater(
                                          () ->
                                              message.setText(
                                                  x == null ? "Configuration saved" : error(x))));
                        } catch (Exception x) {
                          message.setText(error(x));
                        }
                    }));
  }

  private void analytics() {
    workspace
        .getChildren()
        .addAll(
            range(),
            label(
                "Up to 2,000 most recent samples in the selected received-time range. Export"
                    + " preserves source and validity.",
                "caption"),
            button("Export CSV…", this::export));
    double average =
        history.stream()
            .map(r -> r.at("/telemetry/health/heartRateBpm"))
            .filter(JsonNode::isNumber)
            .mapToDouble(JsonNode::asDouble)
            .average()
            .orElse(Double.NaN);
    workspace
        .getChildren()
        .addAll(
            panel(
                "Range summary",
                label(
                    history.size()
                        + " samples · mean heart rate "
                        + (Double.isNaN(average)
                            ? "unavailable"
                            : String.format(Locale.ROOT, "%.1f bpm", average)),
                    "subtitle")),
            panel(
                "History",
                table(
                    history,
                    List.of("Received", "Heart rate", "SpO₂", "Quality", "Source"),
                    r ->
                        List.of(
                            time(Instant.parse(r.path("receivedAt").asText())),
                            text(r.at("/telemetry/health/heartRateBpm")),
                            text(r.at("/telemetry/health/spo2Percent")),
                            r.at("/telemetry/health/quality").asText(),
                            r.at("/telemetry/source").asText()))));
  }

  private void export() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Export telemetry");
    chooser.setInitialFileName("smarttrack-" + device + ".csv");
    var file = chooser.showSaveDialog(stage);
    if (file == null) return;
    List<JsonNode> snapshot = List.copyOf(history);
    java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              StringBuilder out =
                  new StringBuilder(
                      "receivedAt,measuredAt,source,quality,heartRateBpm,spo2Percent,latitude,longitude\r\n");
              for (var r : snapshot) {
                List<String> values =
                    List.of(
                        r.path("receivedAt").asText(),
                        text(r.at("/telemetry/timestamp")),
                        r.at("/telemetry/source").asText(),
                        r.at("/telemetry/health/quality").asText(),
                        text(r.at("/telemetry/health/heartRateBpm")),
                        text(r.at("/telemetry/health/spo2Percent")),
                        text(r.at("/telemetry/gps/latitude")),
                        text(r.at("/telemetry/gps/longitude")));
                out.append(String.join(",", values)).append("\r\n");
              }
              try {
                Files.writeString(file.toPath(), out);
              } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
              }
            })
        .whenComplete(
            (v, e) ->
                Platform.runLater(
                    () ->
                        message.setText(
                            e == null ? "Exported " + snapshot.size() + " samples" : error(e))));
  }

  private void settings() {
    TextField backend = new TextField(api.base()), timezone = new TextField(zone.getId());
    Label result =
        label("Settings are stored locally. Passwords and access tokens stay in memory.", "muted");
    Button save =
        button(
            "Save preferences",
            () -> {
              try {
                ZoneId next = ZoneId.of(timezone.getText());
                URI u = URI.create(backend.getText());
                if (u.getHost() == null || !Set.of("http", "https").contains(u.getScheme()))
                  throw new IllegalArgumentException("Invalid address");
                boolean changed = !api.base().equals(backend.getText());
                api.setBase(backend.getText());
                zone = next;
                prefs.put("backend", api.base());
                prefs.put("timezone", zone.getId());
                result.setText("Preferences saved");
                if (changed) {
                  api.token(null);
                  login();
                }
              } catch (Exception e) {
                result.setText(e.getMessage());
              }
            });
    workspace
        .getChildren()
        .addAll(
            panel(
                "Connection & display",
                label("Backend address", "caption"),
                backend,
                label("Display timezone", "caption"),
                timezone,
                new HBox(
                    8,
                    save,
                    button(
                        "Test connection",
                        () -> {
                          try {
                            new ApiClient(backend.getText())
                                .request("GET", "/actuator/health", null)
                                .whenComplete(
                                    (r, e) ->
                                        Platform.runLater(
                                            () ->
                                                result.setText(
                                                    e == null
                                                        ? "Backend reachable · "
                                                            + r.path("status").asText()
                                                        : error(e))));
                          } catch (Exception e) {
                            result.setText(e.getMessage());
                          }
                        })),
                result),
            panel(
                "Session",
                label("Dark appearance · follows the SmartTrack signal palette.", "muted"),
                button(
                    "Sign out",
                    () -> {
                      api.request("POST", "/v1/auth/logout", null);
                      api.token(null);
                      device = null;
                      latest = null;
                      history.clear();
                      login();
                    })),
            panel(
                "Measurement notice",
                label(
                    "Heart rate, oxygen estimates, impact and fall detection are experimental."
                        + " SmartTrack is not a medical device or an emergency service.",
                    "muted")));
  }

  private Node range() {
    ComboBox<Integer> select = new ComboBox<>(FXCollections.observableArrayList(1, 6, 24, 168));
    select.setValue(hours);
    select.setOnAction(
        e -> {
          hours = select.getValue();
          refresh(true);
        });
    return new HBox(
        8,
        label("History window (hours)", "caption"),
        select,
        button("Load range", () -> refresh(true)));
  }

  private Node chart(String key, String title, String section) {
    NumberAxis x = new NumberAxis(), y = new NumberAxis();
    x.setLabel("Seconds from first sample");
    x.setForceZeroInRange(false);
    y.setLabel(title);
    y.setForceZeroInRange(false);
    LineChart<Number, Number> chart = new LineChart<>(x, y);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setPrefHeight(220);
    chart.setMinHeight(180);
    Runnable update =
        () -> {
          List<XYChart.Series<Number, Number>> series = new ArrayList<>();
          XYChart.Series<Number, Number> segment = null;
          long first =
              history.isEmpty()
                  ? 0
                  : Instant.parse(history.getFirst().path("receivedAt").asText()).toEpochMilli();
          int start = Math.max(0, history.size() - 600);
          for (int i = start; i < history.size(); i++) {
            JsonNode r = history.get(i), v = r.path("telemetry").path(section).path(key);
            if (!v.isNumber()) {
              segment = null;
              continue;
            }
            if (segment == null) {
              segment = new XYChart.Series<>();
              series.add(segment);
            }
            segment
                .getData()
                .add(
                    new XYChart.Data<>(
                        (Instant.parse(r.path("receivedAt").asText()).toEpochMilli() - first)
                            / 1000.0,
                        v.asDouble()));
          }
          chart.getData().setAll(series);
        };
    liveUpdates.add(update);
    return chart;
  }

  private Node table(
      List<JsonNode> rows, List<String> headings, Function<JsonNode, List<String>> mapper) {
    TableView<List<String>> table = new TableView<>();
    for (int i = 0; i < headings.size(); i++) {
      int col = i;
      TableColumn<List<String>, String> c = new TableColumn<>(headings.get(i));
      c.setCellValueFactory(
          v -> new javafx.beans.property.SimpleStringProperty(v.getValue().get(col)));
      table.getColumns().add(c);
    }
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setItems(FXCollections.observableArrayList(rows.stream().map(mapper).toList()));
    table.setPlaceholder(new Label("No samples in this range"));
    table.setPrefHeight(320);
    return table;
  }

  private VBox metric(String name, Supplier<String> value, String unit, Supplier<String> state) {
    Label number = label("—", "measurement"), detail = label("", "caption");
    liveUpdates.add(
        () -> {
          number.setText(value.get() + " " + unit);
          detail.setText(state.get());
        });
    VBox box = panel(name, number, detail);
    box.setMinWidth(240);
    box.setMaxWidth(Double.MAX_VALUE);
    return box;
  }

  private Node field(String name, Supplier<String> value) {
    Label reading = label("—", "reading");
    liveUpdates.add(() -> reading.setText(value.get()));
    HBox row = new HBox(16, label(name, "muted"), new Region(), reading);
    HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
    return row;
  }

  private VBox panel(String title, Node... nodes) {
    VBox v = new VBox(16);
    v.getStyleClass().add("panel");
    v.getChildren().add(label(title, "section-title"));
    v.getChildren().addAll(nodes);
    return v;
  }

  private Label label(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    l.setWrapText(true);
    return l;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(text);
    b.setOnAction(e -> action.run());
    return b;
  }

  private String time(Instant value) {
    return DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(zone).format(value);
  }

  private static String value(Number value, int places) {
    return value == null
        ? "—"
        : String.format(Locale.ROOT, "%." + places + "f", value.doubleValue());
  }

  private static String triple(Double x, Double y, Double z) {
    return value(x, 2) + " / " + value(y, 2) + " / " + value(z, 2);
  }

  private static String text(JsonNode n) {
    return n.isNull() || n.isMissingNode() ? "" : n.asText();
  }

  private static String error(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e.getMessage() == null ? "Connection failed" : e.getMessage();
  }

  @Override
  public void stop() {
    if (ticker != null) ticker.stop();
    if (api != null) api.close();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
