package io.smarttrack.android;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.*;
import io.smarttrack.android.data.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConnectionToolsTest {
  private static String user(Backend api) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("email", UUID.randomUUID() + "@example.test");
    body.addProperty("password", "connection-tools-pass-123");
    assertTrue(api.register(body).execute().isSuccessful());
    return api.login(body).execute().body().get("token").getAsString();
  }

  private static void awaitText(String text) {
    long until = android.os.SystemClock.elapsedRealtime() + 15000;
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    while (android.os.SystemClock.elapsedRealtime() < until) {
      var root = automation.getRootInActiveWindow();
      if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return;
      android.os.SystemClock.sleep(100);
    }
    fail("Missing connection UI: " + text);
  }

  @Test
  public void sharesOnlyOwnedDevicesAndCopiesASecretFreeReport() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    String base =
        InstrumentationRegistry.getArguments().getString("backend", "http://10.0.2.2:8089");
    String networkBackend = InstrumentationRegistry.getArguments().getString("networkBackend");
    if (networkBackend != null) {
      var health = Backend.create(networkBackend, null).health().execute();
      assertTrue("Android cannot reach the laptop LAN API", health.isSuccessful());
      assertEquals("UP", health.body().get("status").getAsString());
    }
    Backend open = Backend.create(base, null);
    String token = user(open), otherToken = user(open);
    Backend owner = Backend.create(base, token), other = Backend.create(base, otherToken);
    String owned = "owned-" + UUID.randomUUID(), foreign = "other-" + UUID.randomUUID();
    for (var pair : List.of(new Object[] {owner, owned}, new Object[] {other, foreign})) {
      JsonObject registration = new JsonObject();
      registration.addProperty("id", (String) pair[1]);
      registration.addProperty("name", "Explicit simulator sharing fixture");
      assertTrue(((Backend) pair[0]).registerDevice(registration).execute().isSuccessful());
    }
    Vault vault = new Vault(context);
    vault.token(token);
    vault
        .preferences()
        .edit()
        .putString("backend", base)
        .putString("device", owned)
        .putString("mode", "BACKEND")
        .putBoolean("sync", false)
        .commit();
    LocalDatabase db = LocalDatabase.get(context);
    String fixture;
    try (var input = instrumentation.getContext().getAssets().open("example-simulator.json")) {
      fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (String device : List.of(owned, foreign)) {
      JsonObject sample = JsonParser.parseString(fixture).getAsJsonObject();
      sample.addProperty("deviceId", device);
      sample.addProperty("timestamp", Instant.now().toString());
      ReadingEntry row = new ReadingEntry();
      row.id = device + "/fixture/0";
      row.deviceId = device;
      row.payload = vault.encrypt(sample.toString());
      row.receivedAt = System.currentTimeMillis();
      row.pendingSync = true;
      db.readings().insert(row);
    }
    SyncEngine.Outcome outcome = SyncEngine.run(vault, db);
    assertEquals(1, outcome.sent());
    assertTrue(db.readings().pendingForDevices(List.of(owned)).isEmpty());
    assertEquals(1, db.readings().pendingForDevices(List.of(foreign)).size());
    assertTrue(owner.latest(owned).execute().isSuccessful());
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      var loaded = new java.util.concurrent.CountDownLatch(1);
      activity.onActivity(
          a -> {
            var model = new androidx.lifecycle.ViewModelProvider(a).get(MonitorViewModel.class);
            model.readings.observe(
                a,
                rows -> {
                  if (!model.selected().isEmpty()) loaded.countDown();
                });
            model.poll();
          });
      assertTrue(
          "Export fixture did not load", loaded.await(15, java.util.concurrent.TimeUnit.SECONDS));
      onView(withId(R.id.more)).perform(click());
      awaitText("Diagnostics");
      onView(withText("Diagnostics")).perform(click());
      awaitText("Connection diagnostics");
      onView(withText("Copy connection report")).perform(scrollTo(), click());
      activity.onActivity(
          a -> {
            var clipboard = a.getSystemService(android.content.ClipboardManager.class);
            String report = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(report.contains("SmartTrack 1.1.0"));
            assertFalse(report.contains("usr_"));
            assertFalse(report.contains("connection-tools-pass-123"));
          });
      onView(withText("Export selected readings as CSV")).perform(scrollTo(), click());
      String filename = "smarttrack-test-" + UUID.randomUUID() + ".csv";
      saveDocument(filename);
      awaitText("Connection diagnostics");
      String csv = readExport(filename);
      assertTrue(csv.startsWith(CsvExport.HEADER));
      assertTrue(csv.contains(owned));
      assertTrue(csv.contains("SIMULATOR"));
      assertFalse(csv.contains(foreign));
      instrumentation.waitForIdleSync();
      var image = instrumentation.getUiAutomation().takeScreenshot();
      try (var output =
          new java.io.FileOutputStream(
              new java.io.File(
                  context.getExternalFilesDir("ui-review"), "connection-diagnostics.png"))) {
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      } finally {
        image.recycle();
      }
    }
  }

  private static void saveDocument(String filename) {
    awaitText("SAVE");
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    var service = automation.getServiceInfo();
    service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(service);
    var root = automation.getRootInActiveWindow();
    var title =
        root.findAccessibilityNodeInfosByViewId("android:id/title").stream()
            .filter(android.view.accessibility.AccessibilityNodeInfo::isEditable)
            .findFirst()
            .orElseThrow();
    var arguments = new android.os.Bundle();
    arguments.putCharSequence(
        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
        filename);
    assertTrue(
        title.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
    var save =
        automation.getRootInActiveWindow().findAccessibilityNodeInfosByText("SAVE").stream()
            .filter(node -> node.isClickable() && node.isEnabled())
            .findFirst()
            .orElseThrow();
    assertTrue(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
  }

  private static String readExport(String filename) throws Exception {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    long until = android.os.SystemClock.elapsedRealtime() + 10000;
    while (android.os.SystemClock.elapsedRealtime() < until) {
      try (var input =
          new android.os.ParcelFileDescriptor.AutoCloseInputStream(
              automation.executeShellCommand("cat /sdcard/Download/" + filename))) {
        String csv = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        if (csv.startsWith(CsvExport.HEADER)) return csv;
      }
      android.os.SystemClock.sleep(200);
    }
    throw new AssertionError("CSV was not saved by the system document picker");
  }
}
