package io.smarttrack.android;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.*;
import io.smarttrack.android.data.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Emulator backend journey uses explicitly simulated measurements. It does not test a BLE radio.
 */
@RunWith(AndroidJUnit4.class)
public class BackendJourneyTest {
  @Test
  public void monitorsPersistedSignalsAndNavigatesEveryScreen() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String base =
        InstrumentationRegistry.getArguments().getString("backend", "http://10.0.2.2:8087");
    Backend unauthenticated = Backend.create(base, null);
    String suffix = UUID.randomUUID().toString();
    JsonObject credentials = new JsonObject();
    credentials.addProperty("email", suffix + "@example.test");
    credentials.addProperty("password", "instrumentation-pass-123");
    assertTrue(unauthenticated.register(credentials).execute().isSuccessful());
    String token = unauthenticated.login(credentials).execute().body().get("token").getAsString();
    Backend api = Backend.create(base, token);
    JsonObject registration = new JsonObject();
    String device = "android-" + suffix;
    registration.addProperty("id", device);
    registration.addProperty("name", "Emulator simulator");
    assertTrue(api.registerDevice(registration).execute().isSuccessful());
    String fixture;
    try (var in =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("example-simulator.json");
        var out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      fixture = new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    JsonObject reading = JsonParser.parseString(fixture).getAsJsonObject();
    reading.addProperty("deviceId", device);
    reading.addProperty("timestamp", Instant.now().toString());
    reading
        .getAsJsonArray("alerts")
        .add(JsonParser.parseString("{\"kind\":\"IMPACT\",\"severity\":\"WARNING\"}"));
    JsonArray batch = new JsonArray();
    batch.add(reading);
    assertTrue(api.sync(batch).execute().isSuccessful());
    Vault vault = new Vault(context);
    vault
        .preferences()
        .edit()
        .putString("backend", base)
        .putString("mode", "BACKEND")
        .putString("device", device)
        .commit();
    vault.token(token);
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      var loaded = new java.util.concurrent.CountDownLatch(1);
      activity.onActivity(
          a -> {
            MonitorViewModel model =
                new androidx.lifecycle.ViewModelProvider(a).get(MonitorViewModel.class);
            model.readings.observe(
                a,
                rows -> {
                  if (!model.selected().isEmpty()) loaded.countDown();
                });
            model.poll();
          });
      assertTrue(
          "Backend reading did not reach encrypted Room cache",
          loaded.await(15, java.util.concurrent.TimeUnit.SECONDS));
      onView(withText("HEART RATE")).check(matches(isDisplayed()));
      onView(withId(R.id.status)).check(matches(withText(containsString("SIMULATED"))));
      capture("home");
      onView(withId(R.id.health)).perform(click());
      onView(withText("Health signals")).check(matches(isDisplayed()));
      capture("health");
      activity.recreate();
      onView(withText("Health signals")).check(matches(isDisplayed()));
      onView(withId(R.id.more)).perform(click());
      awaitText("Motion");
      androidx.test.espresso.Espresso.pressBack();
      awaitText("Health signals");
      activity.onActivity(
          a ->
              assertTrue(
                  ((com.google.android.material.bottomnavigation.BottomNavigationView)
                          a.findViewById(R.id.navigation))
                      .getMenu()
                      .findItem(R.id.health)
                      .isChecked()));
      String[] pages = {"Motion", "Location", "Alerts", "History", "Settings"},
          titles =
              {
                "Motion & orientation",
                "Satellite position",
                "Device event timeline",
                "Recent history",
                "Connection & privacy"
              };
      for (int i = 0; i < pages.length; i++) {
        onView(withId(R.id.more)).perform(click());
        awaitText(pages[i]);
        onView(withText(pages[i])).perform(click());
        awaitText(titles[i]);
        onView(withText(titles[i])).check(matches(isDisplayed()));
        capture(pages[i].toLowerCase(java.util.Locale.ROOT));
      }
      activity.onActivity(
          a -> {
            android.view.ViewGroup content = a.findViewById(R.id.content);
            var editors = new java.util.ArrayList<android.widget.EditText>();
            findEditors(content, editors);
            editors.get(0).setText("https://editing.example.test");
          });
      android.os.SystemClock.sleep(3500);
      onView(withText("https://editing.example.test")).check(matches(isDisplayed()));
      onView(withText("https://editing.example.test")).perform(click());
      androidx.test.espresso.Espresso.closeSoftKeyboard();
      awaitText("Devices");
      onView(withId(R.id.devices)).perform(click());
      onView(withText("Your device connections")).check(matches(isDisplayed()));
      capture("devices");
    }
  }

  private static void findEditors(
      android.view.View node, java.util.List<android.widget.EditText> editors) {
    if (node instanceof android.widget.EditText e) editors.add(e);
    if (node instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) findEditors(group.getChildAt(i), editors);
  }

  private static void capture(String name) throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.waitForIdleSync();
    instrumentation.getUiAutomation().waitForIdle(300, 5000);
    android.graphics.Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(screen);
    java.io.File directory = instrumentation.getTargetContext().getExternalFilesDir("ui-review");
    assertNotNull(directory);
    directory.mkdirs();
    try (var out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
      assertTrue(screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      screen.recycle();
    }
  }

  private static void awaitText(String text) {
    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      var root = automation.getRootInActiveWindow();
      if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return;
      android.os.SystemClock.sleep(100);
    }
    fail("Visible UI text did not appear: " + text);
  }
}
