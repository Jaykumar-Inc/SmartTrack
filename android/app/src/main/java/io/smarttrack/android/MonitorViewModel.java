package io.smarttrack.android;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import androidx.lifecycle.Observer;
import com.google.gson.*;
import io.smarttrack.android.ble.BleController;
import io.smarttrack.android.data.*;
import io.smarttrack.contract.BackendEndpoint;
import io.smarttrack.contract.Telemetry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import retrofit2.*;

public class MonitorViewModel extends AndroidViewModel {
  public final Vault vault;
  public final LocalDatabase db;
  public final BleController ble;
  public final MutableLiveData<String> status = new MutableLiveData<>("OFFLINE · no signal yet");
  public final MutableLiveData<List<ReadingEntry>> readings = new MutableLiveData<>(List.of());
  public final MutableLiveData<JsonArray> devices = new MutableLiveData<>(new JsonArray()),
      alerts = new MutableLiveData<>(new JsonArray());
  public final MutableLiveData<Map<String, String>> discovered = new MutableLiveData<>(Map.of());
  public final MutableLiveData<String> errors = new MutableLiveData<>();
  public final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final LiveData<List<ReadingEntry>> observed;
  private final Observer<List<ReadingEntry>> observer;
  private final Map<String, Telemetry> decoded = new ConcurrentHashMap<>();
  public String device, mode;
  public boolean connected;
  private boolean polling;
  private volatile boolean syncing;
  private long nextSyncAt, nextBackgroundSyncAt;
  public final ConnectionJournal journal = new ConnectionJournal();
  public final MutableLiveData<String> sharing = new MutableLiveData<>("Sharing is off");

  public MonitorViewModel(@NonNull Application app) {
    super(app);
    vault = new Vault(app);
    sharing.postValue(
        vault.preferences().getBoolean("sync", false)
            ? vault.preferences().getString("last_sync_message", "Sharing enabled")
            : "Sharing is off");
    db = LocalDatabase.get(app);
    device = vault.preferences().getString("device", "");
    mode = vault.preferences().getString("mode", "BACKEND");
    ble =
        new BleController(
            app,
            s -> {
              journal.add(s);
              if (s.startsWith("BLE connected")) connected = true;
              else if (s.startsWith("BLE disconnected")) connected = false;
              status.postValue(s);
            },
            payload ->
                worker.execute(
                    () -> {
                      try {
                        Telemetry t = Wire.decode(payload);
                        if (!mode.equals("BLE")) return;
                        device = t.deviceId();
                        vault.preferences().edit().putString("device", device).apply();
                        cache(t, Instant.now().toEpochMilli(), true);
                        connected = true;
                        status.postValue("BLE connected");
                        if (vault.preferences().getBoolean("sync", false)
                            && vault.token() != null
                            && android.os.SystemClock.elapsedRealtime() >= nextBackgroundSyncAt) {
                          nextBackgroundSyncAt = android.os.SystemClock.elapsedRealtime() + 60000;
                          SyncWorker.enqueue(app);
                        }
                      } catch (Exception e) {
                        errors.postValue("Device telemetry rejected: " + e.getMessage());
                      }
                    }));
    observed = db.readings().observe();
    observer =
        rows ->
            worker.execute(
                () -> {
                  try {
                    for (var row : rows)
                      decoded.computeIfAbsent(
                          row.id, id -> Wire.decode(vault.decrypt(row.payload)));
                    Set<String> ids = new HashSet<>();
                    rows.forEach(r -> ids.add(r.id));
                    decoded.keySet().retainAll(ids);
                    readings.postValue(rows);
                  } catch (Exception e) {
                    errors.postValue("Local cache could not be unlocked");
                  }
                });
    observed.observeForever(observer);
    worker.execute(
        () -> {
          db.readings().expire(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
          db.readings().trim();
        });
  }

  public Backend api() {
    return Backend.create(backendAddress(), vault.token());
  }

  public String backendAddress() {
    return BackendSettings.address(vault);
  }

  public void signOut(Runnable completed) {
    worker.execute(
        () -> {
          try {
            api().logout().execute();
          } catch (Exception ignored) {
          }
          vault.token(null);
          vault.preferences().edit().putBoolean("sync", false).apply();
          connected = false;
          devices.postValue(new JsonArray());
          new android.os.Handler(android.os.Looper.getMainLooper())
              .post(
                  () -> {
                    status.setValue("Signed out");
                    completed.run();
                  });
        });
  }

  public void checkConnection(String address, Consumer<String> result) {
    worker.execute(
        () -> {
          String message;
          try {
            Backend probe = Backend.create(address, null);
            Backend.verifyIdentity(probe);
            JsonObject health = checked(probe.health().execute());
            message =
                health.has("status") && health.get("status").getAsString().equals("UP")
                    ? "Backend ready. You can sign in or create an account."
                    : "Backend responded but is not ready.";
          } catch (Exception failure) {
            message = BackendEndpoint.failure(failure, address);
          }
          String output = message;
          journal.add(output);
          new android.os.Handler(android.os.Looper.getMainLooper())
              .post(() -> result.accept(output));
        });
  }

  public void setMode(String next) {
    ble.disconnect();
    connected = false;
    mode = next;
    vault.preferences().edit().putString("mode", mode).apply();
    status.setValue(
        next.equals("BLE")
            ? "BLE · discover a nearby device"
            : "BACKEND · select a registered device");
  }

  public void choose(String id) {
    device = id;
    connected = false;
    vault.preferences().edit().putString("device", id).apply();
    poll();
  }

  public void authenticate(String address, String email, String password, boolean create) {
    worker.execute(
        () -> {
          try {
            String normalized = BackendEndpoint.normalize(address);
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
              throw new IllegalArgumentException("Enter a valid email address");
            if (password.length() < 12
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
              throw new IllegalArgumentException(
                  "Use at least 12 characters and at most 72 UTF-8 bytes for your password");
            Backend api = Backend.create(normalized, null);
            Backend.verifyIdentity(api);
            JsonObject c = new JsonObject();
            c.addProperty("email", email);
            c.addProperty("password", password);
            if (create) checked(api.register(c).execute());
            JsonObject result = checked(api.login(c).execute());
            vault.preferences().edit().putString("backend", normalized).apply();
            vault.token(result.get("token").getAsString());
            status.postValue("Signed in · choose a device");
            loadDevices();
          } catch (Exception e) {
            errors.postValue(BackendEndpoint.failure(e, address));
          }
        });
  }

  public void loadDevices() {
    worker.execute(
        () -> {
          try {
            JsonArray values = checked(api().devices().execute());
            devices.postValue(values);
            if (device.isEmpty() && values.size() > 0) {
              device = values.get(0).getAsJsonObject().get("id").getAsString();
              vault.preferences().edit().putString("device", device).apply();
            }
          } catch (Exception e) {
            errors.postValue(e.getMessage());
          }
        });
  }

  public void poll() {
    if (mode.equals("BLE")) {
      if (vault.preferences().getBoolean("sync", false)
          && vault.token() != null
          && android.os.SystemClock.elapsedRealtime() >= nextSyncAt) syncNow();
      return;
    }
    if (!mode.equals("BACKEND")) return;
    if (vault.token() == null) {
      connected = false;
      status.setValue("Sign in to load backend readings");
      return;
    }
    if (device.isEmpty() || polling) return;
    polling = true;
    String selected = device;
    worker.execute(
        () -> {
          try {
            var r = checked(api().latest(selected).execute());
            if (!selected.equals(device) || !mode.equals("BACKEND")) return;
            Telemetry t = Wire.decode(r.get("telemetry").toString());
            cache(t, Instant.parse(r.get("receivedAt").getAsString()).toEpochMilli(), false);
            connected = true;
            status.postValue("Backend connected");
          } catch (Exception e) {
            connected = false;
            status.postValue("CACHED · backend unavailable or no readings");
          } finally {
            polling = false;
          }
        });
  }

  public void history() {
    if (!mode.equals("BACKEND") || device.isEmpty()) return;
    worker.execute(
        () -> {
          try {
            JsonArray values =
                checked(
                    api()
                        .history(
                            device,
                            Instant.now().minusSeconds(86400).toString(),
                            Instant.now().plusSeconds(1).toString(),
                            500)
                        .execute());
            for (var value : values) {
              var r = value.getAsJsonObject();
              cache(
                  Wire.decode(r.get("telemetry").toString()),
                  Instant.parse(r.get("receivedAt").getAsString()).toEpochMilli(),
                  false);
            }
            status.postValue("Loaded last 24 hours · up to 500 samples");
          } catch (Exception e) {
            errors.postValue(e.getMessage());
          }
        });
  }

  public void loadAlerts() {
    if (!mode.equals("BACKEND") || device.isEmpty()) return;
    worker.execute(
        () -> {
          try {
            alerts.postValue(checked(api().alerts(device).execute()));
          } catch (Exception e) {
            errors.postValue(e.getMessage());
          }
        });
  }

  public void acknowledge(String id) {
    worker.execute(
        () -> {
          try {
            checked(api().acknowledge(id).execute());
            loadAlerts();
          } catch (Exception e) {
            errors.postValue(e.getMessage());
          }
        });
  }

  public void registerDevice(String id, String name, Consumer<String> secret) {
    worker.execute(
        () -> {
          try {
            JsonObject c = new JsonObject();
            c.addProperty("id", id);
            c.addProperty("name", name);
            JsonObject result = checked(api().registerDevice(c).execute());
            new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> secret.accept(result.get("deviceToken").getAsString()));
            loadDevices();
          } catch (Exception e) {
            errors.postValue(e.getMessage());
          }
        });
  }

  public void discover() {
    ble.scan(
        (address, name) -> {
          Map<String, String> next =
              new LinkedHashMap<>(discovered.getValue() == null ? Map.of() : discovered.getValue());
          next.put(address, name);
          discovered.setValue(next);
        });
  }

  public void connectBle(String address) {
    ble.connect(address);
    vault
        .preferences()
        .edit()
        .putString("ble_address", address)
        .putBoolean("ble_autoconnect", true)
        .apply();
  }

  public void disconnectBle() {
    vault.preferences().edit().putBoolean("ble_autoconnect", false).apply();
    ble.stopScan();
    ble.disconnect();
  }

  public void resumeBle() {
    if (!mode.equals("BLE")
        || ble.hasConnection()
        || !vault.preferences().getBoolean("ble_autoconnect", false)) return;
    String address = vault.preferences().getString("ble_address", "");
    if (address.isEmpty()) return;
    try {
      ble.connect(address);
    } catch (RuntimeException failure) {
      status.setValue("BLE reconnect deferred: " + failure.getMessage());
    }
  }

  public void syncNow() {
    if (syncing) return;
    syncing = true;
    worker.execute(
        () -> {
          try {
            SyncEngine.Outcome outcome = SyncEngine.run(vault, db);
            sharing.postValue(outcome.message());
            journal.add(outcome.message());
            nextSyncAt =
                android.os.SystemClock.elapsedRealtime() + (outcome.sent() > 0 ? 3000 : 15000);
          } finally {
            syncing = false;
          }
        });
  }

  public void linkBleDevice() {
    if (vault.token() == null) {
      errors.setValue("Sign in before linking this BLE device to your account");
      return;
    }
    if (device.isEmpty()
        || !mode.equals("BLE")
        || !ble.isReady()
        || selected().isEmpty()
        || decode(selected().get(0)).source() != Telemetry.Source.HARDWARE) {
      errors.setValue("Receive a BLE reading first, then link this device");
      return;
    }
    String id = device;
    worker.execute(
        () -> {
          try {
            JsonArray owned = checked(api().devices().execute());
            boolean exists = false;
            for (var value : owned)
              if (value.getAsJsonObject().get("id").getAsString().equals(id)) exists = true;
            if (!exists) {
              JsonObject body = new JsonObject();
              body.addProperty("id", id);
              body.addProperty("name", "SmartTrack BLE " + id);
              checked(api().registerDevice(body).execute());
            }
            vault.preferences().edit().putBoolean("sync", true).apply();
            sharing.postValue("Device linked. Sharing cached readings...");
            syncNow();
          } catch (Exception failure) {
            errors.postValue(
                "Device linking failed: " + BackendEndpoint.failure(failure, backendAddress()));
          }
        });
  }

  public String connectionReport() {
    StringBuilder report =
        new StringBuilder(
            "SmartTrack "
                + BuildConfig.VERSION_NAME
                + "\nMode: "
                + mode
                + "\nBackend: "
                + backendAddress()
                + "\nAccount: "
                + (vault.token() == null ? "signed out" : "signed in")
                + "\n"
                + ble.diagnostics()
                + "\nCached visible readings: "
                + (readings.getValue() == null ? 0 : readings.getValue().size())
                + "\nSharing: "
                + vault.preferences().getString("last_sync_message", "not run"));
    if (!selected().isEmpty()) {
      Telemetry t = decode(selected().get(0));
      report
          .append("\nSource: ")
          .append(t.source())
          .append("\nHealth quality: ")
          .append(t.health().quality())
          .append("\nGPS characters: ")
          .append(t.gps().characters())
          .append("\nClock: ")
          .append(t.timestampQuality())
          .append("\nFirmware: ")
          .append(t.status().firmwareVersion());
    }
    report
        .append("\nPackage: ")
        .append(BuildConfig.APPLICATION_ID)
        .append("\n")
        .append(NetworkDiagnostics.describe(getApplication()));
    return report.append("\n\nRecent connection events\n").append(journal.text()).toString();
  }

  public void cache(Telemetry t, long received, boolean pending) {
    ReadingEntry entry = new ReadingEntry();
    entry.id = t.deviceId() + "/" + t.sessionId() + "/" + t.sequence();
    entry.deviceId = t.deviceId();
    entry.payload = vault.encrypt(Wire.JSON.toJson(t));
    entry.receivedAt = received;
    entry.pendingSync = pending;
    db.readings().insert(entry);
    db.readings().trim();
  }

  public List<ReadingEntry> selected() {
    List<ReadingEntry> all = readings.getValue();
    return all == null
        ? List.of()
        : all.stream()
            .filter(r -> r.deviceId.equals(device) && decoded.containsKey(r.id))
            .sorted(
                Comparator.comparingLong(
                        (ReadingEntry r) -> {
                          Telemetry t = decoded.get(r.id);
                          return t.timestamp() == null
                              ? r.receivedAt
                              : t.timestamp().toEpochMilli();
                        })
                    .reversed())
            .toList();
  }

  public Telemetry decode(ReadingEntry row) {
    Telemetry t = decoded.get(row.id);
    if (t == null) throw new IllegalStateException("Cache is loading");
    return t;
  }

  private static <T> T checked(Response<T> response) throws Exception {
    if (!response.isSuccessful()) {
      String detail =
          response.code() == 401
              ? "Session expired or invalid credentials. Sign in again."
              : response.code() == 404
                  ? "SmartTrack endpoint not found. Check the backend address and port 8087."
                  : "Request failed (" + response.code() + "). Test the backend connection.";
      if (response.errorBody() != null)
        try {
          JsonObject error =
              JsonParser.parseString(response.errorBody().string()).getAsJsonObject();
          if (error.has("detail")) detail = error.get("detail").getAsString();
        } catch (Exception ignored) {
        }
      throw new IllegalStateException(detail);
    }
    if (response.body() == null) throw new IllegalStateException("Empty backend response");
    return response.body();
  }

  @Override
  protected void onCleared() {
    ble.close();
    observed.removeObserver(observer);
    worker.shutdownNow();
  }
}
