package io.smarttrack.android;

import static org.junit.Assert.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.*;
import io.smarttrack.android.data.*;
import io.smarttrack.contract.Telemetry;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Physical ESP32 scan/pair/live-data check; uses the user's current account without replacing it.
 */
@RunWith(AndroidJUnit4.class)
public class PhysicalBleTest {
  @Test
  public void discoversAndReceivesActualEsp32() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var args = InstrumentationRegistry.getArguments();
    String address = args.getString("address"), device = args.getString("device");
    assertNotNull(address);
    assertNotNull(device);
    long started = System.currentTimeMillis();
    var records = new CountDownLatch(3);
    var linked = new CountDownLatch(1);
    var seen = ConcurrentHashMap.<String>newKeySet();
    var connected = new java.util.concurrent.atomic.AtomicBoolean();
    JsonObject report = new JsonObject();
    report.addProperty("device", device);
    var directory = instrumentation.getTargetContext().getExternalFilesDir("diagnostics");
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(
          a -> {
            a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            var model = new androidx.lifecycle.ViewModelProvider(a).get(MonitorViewModel.class);
            model.status.observe(
                a,
                message -> {
                  try (var writer =
                      new java.io.FileWriter(
                          new java.io.File(directory, "physical-ble-status.log"), true)) {
                    writer.write(message + "\n");
                  } catch (java.io.IOException ignored) {
                  }
                });
            model.readings.observe(
                a,
                rows -> {
                  if (model.selected().isEmpty()) return;
                  var row = model.selected().get(0);
                  var t = model.decode(row);
                  if (t.source() == Telemetry.Source.HARDWARE
                      && t.deviceId().equals(device)
                      && row.receivedAt >= started
                      && seen.add(row.id)) records.countDown();
                });
            model.discovered.observe(
                a,
                values -> {
                  if (values.containsKey(address) && connected.compareAndSet(false, true))
                    model.connectBle(address);
                });
            model.sharing.observe(
                a,
                message -> {
                  if (message != null && message.startsWith("Shared ")) linked.countDown();
                });
            model.setMode("BLE");
            model.discover();
          });
      assertTrue(
          "No three complete hardware readings; inspect physical-ble-status.log",
          records.await(100, TimeUnit.SECONDS));
      report.addProperty("distinctHardwareReadings", seen.size());
      report.addProperty("discoveryMatched", connected.get());
      activity.onActivity(
          a -> {
            var model = new androidx.lifecycle.ViewModelProvider(a).get(MonitorViewModel.class);
            var t = model.decode(model.selected().get(0));
            report.addProperty("firmware", t.status().firmwareVersion());
            report.addProperty("healthQuality", t.health().quality().name());
            report.addProperty("accountPresent", model.vault.token() != null);
            if ("true".equals(args.getString("link")) && model.vault.token() != null)
              model.linkBleDevice();
          });
      if ("true".equals(args.getString("link")) && report.get("accountPresent").getAsBoolean()) {
        assertTrue("User account sharing did not complete", linked.await(25, TimeUnit.SECONDS));
        report.addProperty("sharedWithBackend", true);
      }
      instrumentation.waitForIdleSync();
      var image = instrumentation.getUiAutomation().takeScreenshot();
      try (var output =
          new java.io.FileOutputStream(new java.io.File(directory, "physical-live.png"))) {
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      } finally {
        image.recycle();
      }
    } finally {
      try (var writer =
          new java.io.OutputStreamWriter(
              new java.io.FileOutputStream(new java.io.File(directory, "physical-ble-result.json")),
              java.nio.charset.StandardCharsets.UTF_8)) {
        writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(report));
      }
    }
  }
}
