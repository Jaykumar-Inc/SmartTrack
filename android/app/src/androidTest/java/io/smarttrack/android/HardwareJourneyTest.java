package io.smarttrack.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.smarttrack.android.data.Vault;
import io.smarttrack.contract.Telemetry;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Reads actual ESP32 telemetry previously ingested over laptop BLE; no simulated fixture. */
@RunWith(AndroidJUnit4.class)
public class HardwareJourneyTest {
  @Test
  public void displaysRealEsp32ReadingsFromBackend() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var args = InstrumentationRegistry.getArguments();
    String token = args.getString("token"), device = args.getString("device");
    assertNotNull("Supply an ephemeral test user token", token);
    assertNotNull("Supply the real ESP32 device ID", device);
    Vault vault = new Vault(instrumentation.getTargetContext());
    vault
        .preferences()
        .edit()
        .putString("backend", args.getString("backend", "http://127.0.0.1:8087"))
        .putString("device", device)
        .putString("mode", "BACKEND")
        .commit();
    vault.token(token);
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      var loaded = new CountDownLatch(1);
      activity.onActivity(
          a -> {
            var model = new androidx.lifecycle.ViewModelProvider(a).get(MonitorViewModel.class);
            model.readings.observe(
                a,
                rows -> {
                  if (!model.selected().isEmpty()
                      && model.decode(model.selected().get(0)).source()
                          == Telemetry.Source.HARDWARE) loaded.countDown();
                });
            model.poll();
          });
      assertTrue(
          "Actual ESP32 reading did not reach the Android cache",
          loaded.await(20, TimeUnit.SECONDS));
      onView(withId(R.id.status)).check(matches(withText(containsString("HARDWARE"))));
      onView(withText("HEART RATE")).check(matches(isDisplayed()));
      onView(withId(R.id.health)).perform(click());
      onView(withText("Health signals")).check(matches(isDisplayed()));
      instrumentation.waitForIdleSync();
      instrumentation.getUiAutomation().waitForIdle(300, 5000);
      var image = instrumentation.getUiAutomation().takeScreenshot();
      var directory = instrumentation.getTargetContext().getExternalFilesDir("ui-review");
      try (var output =
          new java.io.FileOutputStream(new java.io.File(directory, "hardware-health.png"))) {
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      } finally {
        image.recycle();
      }
    }
  }
}
