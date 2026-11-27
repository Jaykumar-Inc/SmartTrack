package org.smarttrack.desktop;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Opt-in UI regression against an isolated HTTP fixture; never writes production readings. */
public final class DesktopRefreshSmoke {
  public static void main(String[] args) {
    Application.launch(RefreshApp.class, args);
  }

  public static final class RefreshApp extends Application {
    private final AtomicInteger requests = new AtomicInteger();
    private final Preferences prefs = Preferences.userNodeForPackage(SmartTrackDesktop.class);
    private String oldBackend, oldUsername;
    private HttpServer server;
    private SmartTrackDesktop app;
    private Stage stage;
    private Timeline checks;
    private int step, before, waits;
    private String previousName;

    @Override
    public void start(Stage stage) throws Exception {
      this.stage = stage;
      oldBackend = prefs.get("backend", null);
      oldUsername = prefs.get("username", null);
      prefs.putInt("refresh", 1);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            int code = 200;
            if (path.equals("/api/auth/me")) body = "{\"username\":\"refresh-fixture\"}";
            else if (path.equals("/api/devices"))
              body =
                  "[{\"deviceId\":\"refresh-test\",\"name\":\"Refresh check "
                      + requests.incrementAndGet()
                      + "\",\"simulated\":true,\"online\":false}]";
            else if (path.endsWith("/history") || path.endsWith("/alerts")) body = "[]";
            else {
              code = 404;
              body = "{}";
            } // Polling must work even without WebSocket support.
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          });
      server.start();
      app = new SmartTrackDesktop();
      app.start(stage);
      ((TextField) stage.getScene().lookup("#backend"))
          .setText("http://127.0.0.1:" + server.getAddress().getPort());
      ((TextField) stage.getScene().lookup("#username")).setText("refresh-fixture");
      ((PasswordField) stage.getScene().lookup("#password")).setText("fixture-only-password");
      ((Button) stage.getScene().lookup("#signin")).fire();
      checks = new Timeline(new KeyFrame(Duration.seconds(3), e -> check()));
      checks.setCycleCount(Timeline.INDEFINITE);
      checks.play();
    }

    private Button button(String label) {
      return stage.getScene().getRoot().lookupAll(".button").stream()
          .filter(n -> ((Button) n).getText().equals(label))
          .map(n -> (Button) n)
          .findFirst()
          .orElseThrow();
    }

    private String deviceName() {
      return stage.getScene().getRoot().lookupAll(".label").stream()
          .map(n -> ((Label) n).getText())
          .filter(t -> t.startsWith("Refresh check "))
          .findFirst()
          .orElseThrow();
    }

    private void check() {
      try {
        if (step == 0) {
          if (stage.getScene().lookup("#auto-refresh-status") == null) {
            if (waits++ > 6) throw new AssertionError("Login timed out");
            return;
          }
          button("Devices").fire();
          before = requests.get();
          previousName = deviceName();
          step++;
          return;
        }
        if (step == 1) {
          if (requests.get() <= before || deviceName().equals(previousName))
            throw new AssertionError("Devices did not refresh automatically");
          String label = ((Label) stage.getScene().lookup("#auto-refresh-status")).getText();
          if (!label.startsWith("Auto refresh: 1 s | updated ")) throw new AssertionError(label);
          button("Disconnect").fire();
          before = requests.get();
          step++;
          return;
        }
        if (step == 2) {
          if (requests.get() > before + 1)
            throw new AssertionError("Polling continued while disconnected");
          if (!((Label) stage.getScene().lookup("#auto-refresh-status"))
              .getText()
              .equals("Auto refresh paused")) throw new AssertionError("Paused indicator missing");
          button("Connect").fire();
          before = requests.get();
          step++;
          return;
        }
        if (requests.get() <= before) throw new AssertionError("Polling did not resume");
        System.out.println(
            "DESKTOP_AUTO_REFRESH_PASSED: automatic Devices redraw, one-second polling without"
                + " WebSocket, pause and resume; no Refresh button used");
        checks.stop();
        app.stop();
        server.stop(0);
        restore();
        stage.close();
        Platform.exit();
      } catch (Throwable error) {
        error.printStackTrace();
        if (checks != null) checks.stop();
        if (app != null) app.stop();
        if (server != null) server.stop(0);
        restore();
        Platform.exit();
        System.exit(1);
      }
    }

    private void restore() {
      if (oldBackend == null) prefs.remove("backend");
      else prefs.put("backend", oldBackend);
      if (oldUsername == null) prefs.remove("username");
      else prefs.put("username", oldUsername);
    }

    @Override
    public void stop() {
      if (app != null) app.stop();
      if (server != null) server.stop(0);
      restore();
    }
  }
}
