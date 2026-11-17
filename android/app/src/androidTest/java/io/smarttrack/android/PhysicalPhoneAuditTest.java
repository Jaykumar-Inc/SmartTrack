package io.smarttrack.android;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.*;
import io.smarttrack.android.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Audits only SmartTrack/network state on an authorized phone. Does not replace accounts or caches.
 */
@RunWith(AndroidJUnit4.class)
public class PhysicalPhoneAuditTest {
  @Test
  public void checkActualPhoneBackend() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var args = InstrumentationRegistry.getArguments();
    Vault vault = new Vault(context);
    String base = args.getString("backend", BackendSettings.address(vault));
    if ("true".equals(args.getString("saveBackend"))) {
      String normalized = io.smarttrack.contract.BackendEndpoint.normalize(base);
      vault.preferences().edit().putString("backend", normalized).commit();
    }
    JsonObject report = new JsonObject();
    report.addProperty("version", BuildConfig.VERSION_NAME);
    report.addProperty("backend", base);
    report.addProperty("network", NetworkDiagnostics.describe(context));
    report.addProperty("sessionPresent", vault.token() != null);
    boolean ready = false;
    try {
      Backend api = Backend.create(base, null);
      Backend.verifyIdentity(api);
      var health = api.health().execute();
      ready = health.isSuccessful() && "UP".equals(health.body().get("status").getAsString());
      report.addProperty("backendReady", ready);
      if (vault.token() != null) {
        var registry = Backend.create(base, vault.token()).devices().execute();
        report.addProperty("registryHttpStatus", registry.code());
        if (registry.isSuccessful())
          report.addProperty("registeredDevices", registry.body().size());
      }
    } catch (Exception failure) {
      report.addProperty("error", io.smarttrack.contract.BackendEndpoint.failure(failure, base));
    }
    var folder = context.getExternalFilesDir("diagnostics");
    try (var writer =
        new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(new java.io.File(folder, "phone-audit.json")),
            java.nio.charset.StandardCharsets.UTF_8)) {
      writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    assertTrue(
        "Physical phone cannot reach the configured SmartTrack backend; see phone-audit.json",
        ready);
  }
}
