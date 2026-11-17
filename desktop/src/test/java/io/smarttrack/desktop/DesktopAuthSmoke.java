package io.smarttrack.desktop;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;

/** Exercises the real JavaFX login form against a running backend, without a preset token. */
public final class DesktopAuthSmoke {
  private static Stage stage;
  private static SmartTrackDesktop app;

  private static <T> T fx(Callable<T> action) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  private static Node node(String id) {
    return stage.getScene().lookup("#" + id);
  }

  private static List<Node> all(Node node) {
    List<Node> nodes = new ArrayList<>();
    nodes.add(node);
    if (node instanceof Parent parent)
      for (Node child : parent.getChildrenUnmodifiable()) nodes.addAll(all(child));
    return nodes;
  }

  private static void button(String text) {
    ((Button)
            all(stage.getScene().getRoot()).stream()
                .filter(n -> n instanceof Button b && b.getText().equals(text))
                .findFirst()
                .orElseThrow())
        .fire();
  }

  private static void waitFor(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (fx(condition::getAsBoolean)) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Desktop auth UI timed out");
  }

  private static void fill(String email, String password) {
    ((TextField) node("login-email")).setText(email);
    ((PasswordField) node("login-password")).setText(password);
  }

  private static boolean message(String text) {
    return node("login-result") instanceof Label label && label.getText().contains(text);
  }

  private static void signOut() throws Exception {
    fx(
        () -> {
          button("Settings");
          button("Sign out");
          return null;
        });
    waitFor(() -> node("login-sign-in") != null);
  }

  public static void main(String[] args) throws Exception {
    Platform.startup(() -> {});
    Platform.setImplicitExit(false);
    String email = "desktop-" + UUID.randomUUID() + "@example.test",
        password = "desktop-auth-test-123";
    try {
      fx(
          () -> {
            app = new SmartTrackDesktop();
            stage = new Stage();
            app.start(stage);
            return null;
          });
      fx(
          () -> {
            ((Button) node("login-test")).fire();
            return null;
          });
      waitFor(() -> message("Backend ready"));
      fx(
          () -> {
            fill(email, "short");
            ((Button) node("login-register")).fire();
            return null;
          });
      waitFor(() -> message("12 characters"));
      fx(
          () -> {
            fill(email, password);
            ((Button) node("login-register")).fire();
            return null;
          });
      waitFor(() -> node("login-register") == null);
      signOut();
      fx(
          () -> {
            fill(email, "wrong-password-123");
            ((Button) node("login-sign-in")).fire();
            return null;
          });
      waitFor(() -> message("Invalid email or password"));
      fx(
          () -> {
            fill(email, password);
            ((Button) node("login-sign-in")).fire();
            return null;
          });
      waitFor(() -> node("login-sign-in") == null);
      signOut();
      fx(
          () -> {
            fill(email, password);
            ((Button) node("login-register")).fire();
            return null;
          });
      waitFor(() -> message("Sign in instead"));
      System.out.println(
          "PASS: JavaFX connection test, validation, account creation, logout, wrong password,"
              + " sign-in and duplicate account feedback");
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
