package org.smarttrack.desktop;

import java.nio.file.*;
import java.util.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Opt-in real JavaFX event-handler smoke test against a running local backend. */
public final class DesktopSmoke {
  public static void main(String[] args) {
    Application.launch(SmokeApp.class, args);
  }

  public static final class SmokeApp extends Application {
    SmartTrackDesktop app;
    Stage stage;
    Timeline steps;
    int step = 0, waits = 0;
    final List<String> pages =
        List.of(
            "Dashboard",
            "Live Health",
            "Temperature",
            "Activity",
            "Alerts",
            "Devices",
            "Settings",
            "Dashboard");

    public void start(Stage stage) {
      this.stage = stage;
      app = new SmartTrackDesktop();
      app.start(stage);
      ((TextField) stage.getScene().lookup("#backend")).setText(System.getenv("SMOKE_BACKEND"));
      ((TextField) stage.getScene().lookup("#username")).setText(System.getenv("SMARTTRACK_USER"));
      ((PasswordField) stage.getScene().lookup("#password"))
          .setText(System.getenv("SMARTTRACK_PASSWORD"));
      ((Button) stage.getScene().lookup("#signin")).fire();
      steps = new Timeline(new KeyFrame(Duration.seconds(2), e -> tick()));
      steps.setCycleCount(Timeline.INDEFINITE);
      steps.play();
    }

    void tick() {
      try {
        if (step == 0
            && stage.getScene().getRoot().lookupAll(".button").stream()
                .noneMatch(n -> ((Button) n).getText().equals("Devices"))) {
          if (waits++ > 20) throw new IllegalStateException("Local backend login did not complete");
          return;
        }
        if (step < pages.size()) {
          String name = pages.get(step++);
          Button b =
              stage.getScene().getRoot().lookupAll(".button").stream()
                  .filter(n -> ((Button) n).getText().equals(name))
                  .map(n -> (Button) n)
                  .findFirst()
                  .orElseThrow();
          b.fire();
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          Path file =
              Path.of(System.getenv("SMOKE_OUTPUT"), "desktop-" + name.replace(' ', '-') + ".png");
          ImageIO.write(
              SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null),
              "png",
              file.toFile());
          System.out.println("Verified navigation event: " + name);
        } else {
          stage.getScene().getRoot().lookupAll(".button").stream()
              .filter(n -> ((Button) n).getText().equals("Logout"))
              .map(n -> (Button) n)
              .findFirst()
              .orElseThrow()
              .fire();
          if (stage.getScene().lookup("#signin") == null)
            throw new IllegalStateException("Logout did not show login");
          System.out.println("DESKTOP_SMOKE_PASSED");
          steps.stop();
          app.stop();
          stage.close();
          Platform.exit();
        }
      } catch (Exception e) {
        e.printStackTrace();
        steps.stop();
        app.stop();
        Platform.exit();
        System.exit(1);
      }
    }

    public void stop() {
      if (app != null) app.stop();
    }
  }
}
