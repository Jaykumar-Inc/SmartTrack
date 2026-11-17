package io.smarttrack.desktop;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/** App-level UI harness, invoked separately with simulator credentials and an actual backend. */
public final class DesktopSmoke {
  private static Stage stage;
  private static SmartTrackDesktop app;

  private static <T> T fx(Callable<T> action) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Throwable e) {
            result.completeExceptionally(e);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  private static List<javafx.scene.Node> all(javafx.scene.Node node) {
    List<javafx.scene.Node> result = new ArrayList<>();
    result.add(node);
    if (node instanceof Parent p)
      for (var child : p.getChildrenUnmodifiable()) result.addAll(all(child));
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (System.getenv("SMARTTRACK_USER_TOKEN") == null)
      throw new IllegalStateException(
          "Set SMARTTRACK_USER_TOKEN and SMARTTRACK_BACKEND to the test server");
    Platform.startup(() -> {});
    Platform.setImplicitExit(false);
    try {
      fx(
          () -> {
            app = new SmartTrackDesktop();
            stage = new Stage();
            app.start(stage);
            return null;
          });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      String expectedSource =
          Optional.ofNullable(System.getenv("SMARTTRACK_TEST_SOURCE")).orElse("SIMULATED");
      String selectedDevice = System.getenv("SMARTTRACK_TEST_DEVICE");
      boolean signal = false;
      while (System.nanoTime() < deadline) {
        signal =
            fx(
                () -> {
                  if (selectedDevice != null) {
                    all(stage.getScene().getRoot()).stream()
                        .filter(
                            n ->
                                n instanceof ComboBox<?> combo
                                    && combo.getItems().contains(selectedDevice))
                        .findFirst()
                        .ifPresent(n -> ((ComboBox<String>) n).setValue(selectedDevice));
                  }
                  return all(stage.getScene().getRoot()).stream()
                      .filter(n -> n instanceof Label)
                      .anyMatch(n -> ((Label) n).getText().contains(expectedSource));
                });
        if (signal) break;
        Thread.sleep(200);
      }
      if (!signal) throw new AssertionError(expectedSource + " signal did not reach JavaFX");
      Path output = Path.of(".work", expectedSource.equals("HARDWARE") ? "ui-hardware" : "ui");
      Files.createDirectories(output);
      for (String screen :
          List.of(
              "Overview",
              "Health",
              "Motion",
              "Location",
              "Alerts",
              "Devices",
              "Analytics",
              "Settings")) {
        fx(
            () -> {
              Button button =
                  (Button)
                      all(stage.getScene().getRoot()).stream()
                          .filter(
                              n ->
                                  n instanceof Button b
                                      && b.getStyleClass().contains("nav")
                                      && b.getText().equals(screen))
                          .findFirst()
                          .orElseThrow();
              button.fire();
              return null;
            });
        Thread.sleep(400);
        fx(
            () -> {
              stage.getScene().getRoot().applyCss();
              stage.getScene().getRoot().layout();
              ImageIO.write(
                  SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null),
                  "png",
                  output.resolve(screen.toLowerCase(Locale.ROOT) + ".png").toFile());
              return null;
            });
        System.out.println("UI screen rendered: " + screen);
      }
      // Streaming must not reconstruct settings while a user is editing.
      fx(
          () -> {
            TextField input =
                (TextField)
                    all(stage.getScene().getRoot()).stream()
                        .filter(n -> n instanceof TextField)
                        .findFirst()
                        .orElseThrow();
            input.setText("http://editing-in-progress.test");
            return null;
          });
      Thread.sleep(3500);
      boolean preserved =
          fx(
              () ->
                  all(stage.getScene().getRoot()).stream()
                      .anyMatch(
                          n ->
                              n instanceof TextField f
                                  && f.getText().equals("http://editing-in-progress.test")));
      if (!preserved) throw new AssertionError("Live updates reset the settings form");
      System.out.println(
          "PASS: "
              + expectedSource
              + " -> Spring Boot -> database -> JavaFX; eight screens and edit"
              + " preservation");
    } finally {
      fx(
          () -> {
            if (app != null) app.stop();
            if (stage != null) stage.close();
            return null;
          });
      Platform.exit();
    }
  }
}
