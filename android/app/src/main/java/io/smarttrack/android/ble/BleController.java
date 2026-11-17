package io.smarttrack.android.ble;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.location.LocationManager;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

@SuppressLint("MissingPermission")
public final class BleController implements AutoCloseable {
  public static final UUID SERVICE = UUID.fromString("7fd90000-9c24-4f64-a65e-8a08cc91f001"),
      SNAPSHOT = UUID.fromString("7fd90002-9c24-4f64-a65e-8a08cc91f001");
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private final Context context;
  private final BluetoothAdapter adapter;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Consumer<String> status, reading;
  private final FrameAssembler assembler = new FrameAssembler();
  private BluetoothGatt gatt;
  private BluetoothDevice target;
  private boolean reconnect, linkConnected, scanning, closed;
  private int retries;
  private long completeReadings;
  private int negotiatedMtu = 23;
  private String phase = "IDLE";
  private BiConsumer<String, String> found;
  private Runnable timeout, scanStop, retry;

  public BleController(Context context, Consumer<String> status, Consumer<String> reading) {
    this.context = context.getApplicationContext();
    this.status = status;
    this.reading = reading;
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
    ContextCompat.registerReceiver(
        this.context,
        bondReceiver,
        new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        ContextCompat.RECEIVER_EXPORTED);
  }

  private final BroadcastReceiver bondReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          BluetoothDevice device =
              Build.VERSION.SDK_INT >= 33
                  ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                  : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          if (target == null || !target.equals(device) || gatt == null) return;
          int state =
              intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
          if (state == BluetoothDevice.BOND_BONDED && linkConnected && phase.equals("PAIRING"))
            negotiate(gatt);
          else if (state == BluetoothDevice.BOND_NONE && phase.equals("PAIRING"))
            fail(
                "Pairing cancelled or failed. Forget this device in Bluetooth settings, then"
                    + " reconnect using its OLED code.");
        }
      };

  private final ScanCallback scan =
      new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
          dispatch(
              () -> {
                if (!scanning || found == null) return;
                BluetoothDevice d = result.getDevice();
                found.accept(d.getAddress(), d.getName() == null ? "SmartTrack" : d.getName());
              });
        }

        @Override
        public void onScanFailed(int error) {
          dispatch(
              () -> {
                scanning = false;
                status.accept("BLE scan failed (" + error + "). Wait a few seconds and retry.");
              });
        }
      };

  private void dispatch(Runnable action) {
    handler.post(
        () -> {
          if (closed) return;
          try {
            action.run();
          } catch (SecurityException denied) {
            fail("Bluetooth permission was revoked. Allow Nearby devices in app settings.");
          }
        });
  }

  public boolean isReady() {
    return gatt != null && phase.equals("READY");
  }

  public boolean hasConnection() {
    return gatt != null;
  }

  private void requireAdapter() {
    if (closed) throw new IllegalStateException("Bluetooth controller is closed");
    if (adapter == null) throw new IllegalStateException("This device has no Bluetooth LE adapter");
    if (!adapter.isEnabled())
      throw new IllegalStateException("Turn on Bluetooth, then discover devices again");
  }

  public void scan(BiConsumer<String, String> found) {
    requireAdapter();
    stopScan();
    if (Build.VERSION.SDK_INT <= 30) {
      LocationManager location = context.getSystemService(LocationManager.class);
      if (location != null
          && !location.isProviderEnabled(LocationManager.GPS_PROVIDER)
          && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        throw new IllegalStateException(
            "Enable Location on this Android version to discover BLE devices");
    }
    this.found = found;
    // Paired devices are useful even if another client currently holds the peripheral connection.
    for (BluetoothDevice d : adapter.getBondedDevices())
      if (d.getName() != null && d.getName().startsWith("SmartTrack"))
        found.accept(d.getAddress(), d.getName());
    var scanner = adapter.getBluetoothLeScanner();
    if (scanner == null)
      throw new IllegalStateException(
          "Bluetooth scanner is unavailable. Toggle Bluetooth and retry.");
    scanning = true;
    scanner.startScan(
        List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
        scan);
    status.accept("Scanning for SmartTrack devices...");
    scanStop =
        () -> {
          stopScan();
          status.accept(
              "Scan finished. Choose a device. Disconnect other phones or laptops if it is busy.");
        };
    handler.postDelayed(scanStop, 10000);
  }

  public void stopScan() {
    if (scanStop != null) handler.removeCallbacks(scanStop);
    try {
      if (scanning
          && adapter != null
          && adapter.isEnabled()
          && adapter.getBluetoothLeScanner() != null)
        adapter.getBluetoothLeScanner().stopScan(scan);
    } catch (SecurityException revoked) {
      status.accept("Bluetooth permission unavailable");
    }
    scanning = false;
  }

  public void connect(String address) {
    requireAdapter();
    stopScan();
    disconnect();
    target = adapter.getRemoteDevice(address);
    reconnect = true;
    retries = 0;
    connectNow();
  }

  public String diagnostics() {
    try {
      return "Bluetooth: "
          + (adapter == null ? "not supported" : adapter.isEnabled() ? "on" : "off")
          + "\nBLE stage: "
          + phase
          + "\nMTU: "
          + negotiatedMtu
          + "\nComplete BLE records: "
          + completeReadings
          + "\nReconnect attempts: "
          + retries;
    } catch (SecurityException denied) {
      return "Bluetooth permission is missing. Allow Nearby devices in Android app settings.";
    }
  }

  private void armTimeout(BluetoothGatt expected, long millis, String message) {
    cancelTimeout();
    timeout =
        () -> {
          if (gatt == expected) fail(message);
        };
    handler.postDelayed(timeout, millis);
  }

  private void cancelTimeout() {
    if (timeout != null) handler.removeCallbacks(timeout);
    timeout = null;
  }

  private void connectNow() {
    if (!reconnect || target == null || closed) return;
    assembler.clear();
    negotiatedMtu = 23;
    phase = "CONNECTING";
    linkConnected = false;
    status.accept("BLE connecting. Keep the ESP32 nearby.");
    try {
      gatt = target.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
      if (gatt == null) {
        fail("Android could not open the BLE connection");
        return;
      }
      armTimeout(gatt, 30000, "BLE connection timed out. Disconnect other clients and retry.");
    } catch (SecurityException revoked) {
      fail("Bluetooth permission unavailable");
    }
  }

  private void negotiate(BluetoothGatt g) {
    if (g != gatt
        || !linkConnected
        || phase.equals("MTU")
        || phase.equals("SERVICES")
        || phase.equals("READY")) return;
    phase = "MTU";
    status.accept("Paired. Negotiating BLE connection...");
    if (!g.requestMtu(185)) discover(g);
    else {
      cancelTimeout();
      timeout =
          () -> {
            if (g == gatt && phase.equals("MTU")) discover(g);
          };
      handler.postDelayed(timeout, 4000);
    }
  }

  private void discover(BluetoothGatt g) {
    if (g != gatt || !phase.equals("MTU")) return;
    phase = "SERVICES";
    status.accept("Paired. Discovering SmartTrack services...");
    armTimeout(g, 15000, "BLE service discovery timed out. Reconnect the device.");
    if (!g.discoverServices()) fail("BLE discovery could not start. Reconnect the device.");
  }

  private final BluetoothGattCallback callbacks =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int result, int state) {
          dispatch(
              () -> {
                if (g != gatt) {
                  g.close();
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED
                    && result == BluetoothGatt.GATT_SUCCESS) {
                  linkConnected = true;
                  phase = "PAIRING";
                  if (g.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) negotiate(g);
                  else {
                    status.accept(
                        "Pairing: enter the six-digit ESP32 OLED code in the Android pairing"
                            + " prompt.");
                    armTimeout(
                        g,
                        90000,
                        "Pairing timed out. Enter the OLED code when Android asks, then"
                            + " reconnect.");
                    if (g.getDevice().getBondState() == BluetoothDevice.BOND_NONE)
                      g.getDevice().createBond();
                  }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED
                    || result != BluetoothGatt.GATT_SUCCESS) {
                  cancelTimeout();
                  gatt = null;
                  linkConnected = false;
                  assembler.clear();
                  g.close();
                  phase = "IDLE";
                  status.accept("BLE disconnected (" + result + "). Cached readings retained.");
                  if (reconnect && retries < 5) {
                    retry = thisRetry();
                    handler.postDelayed(retry, Math.min(30000, 1000L << ++retries));
                  } else if (reconnect)
                    fail(
                        "BLE reconnect stopped after five attempts. Check power and pairing, then"
                            + " connect again.");
                }
              });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int result) {
          dispatch(
              () -> {
                if (g == gatt && result == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
                discover(g);
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int result) {
          dispatch(
              () -> {
                if (g != gatt || !phase.equals("SERVICES")) return;
                var s = g.getService(SERVICE);
                if (result != BluetoothGatt.GATT_SUCCESS || s == null) {
                  fail("SmartTrack BLE service unavailable. Check the ESP32 firmware.");
                  return;
                }
                var c = s.getCharacteristic(SNAPSHOT);
                if (c == null || !g.setCharacteristicNotification(c, true)) {
                  fail("Snapshot notifications could not be enabled");
                  return;
                }
                var d = c.getDescriptor(CCCD);
                if (d == null) {
                  fail("Snapshot notification descriptor is missing");
                  return;
                }
                phase = "SUBSCRIBING";
                status.accept("Paired. Enabling live readings...");
                armTimeout(g, 15000, "BLE subscription timed out. Reconnect the device.");
                boolean accepted;
                if (Build.VERSION.SDK_INT >= 33)
                  accepted =
                      g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                          == BluetoothStatusCodes.SUCCESS;
                else {
                  d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                  accepted = g.writeDescriptor(d);
                }
                if (!accepted)
                  fail("Android rejected the notification request. Reconnect the device.");
              });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int result) {
          dispatch(
              () -> {
                if (g != gatt || !phase.equals("SUBSCRIBING") || !d.getUuid().equals(CCCD)) return;
                cancelTimeout();
                if (result == BluetoothGatt.GATT_SUCCESS) {
                  phase = "READY";
                  retries = 0;
                  status.accept("BLE connected - awaiting the first complete reading");
                  armTimeout(
                      g,
                      75000,
                      "No complete reading arrived. Check the ESP32 delivery interval and"
                          + " reconnect.");
                  try {
                    write(7, "{\"epochSeconds\":" + java.time.Instant.now().getEpochSecond() + "}");
                  } catch (RuntimeException ignored) {
                    status.accept("BLE connected - clock sync deferred");
                  }
                } else
                  fail(
                      "BLE notification setup failed ("
                          + result
                          + "). Forget the device in Bluetooth settings and pair again using the"
                          + " OLED code.");
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
          if (value == null) return;
          byte[] copy = value.clone();
          dispatch(
              () -> {
                if (g == gatt && c.getUuid().equals(SNAPSHOT)) decode(copy);
              });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
          if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, c, c.getValue());
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int result) {
          dispatch(
              () -> {
                if (g == gatt)
                  status.accept(
                      result == BluetoothGatt.GATT_SUCCESS
                          ? "Device write accepted"
                          : "Device write failed (" + result + ")");
              });
        }
      };

  private Runnable thisRetry() {
    return () -> {
      if (reconnect) connectNow();
    };
  }

  private void decode(byte[] bytes) {
    try {
      String value = assembler.accept(bytes, SystemClock.elapsedRealtime());
      if (value != null) {
        completeReadings++;
        cancelTimeout();
        reading.accept(value);
      }
    } catch (Exception e) {
      status.accept("Incomplete or invalid BLE telemetry");
    }
  }

  public void write(int suffix, String json) {
    if (gatt == null || !phase.equals("READY"))
      throw new IllegalStateException("Wait until the paired BLE connection is ready");
    var s = gatt.getService(SERVICE);
    var c =
        s == null
            ? null
            : s.getCharacteristic(
                UUID.fromString(
                    String.format(Locale.ROOT, "7fd900%02x-9c24-4f64-a65e-8a08cc91f001", suffix)));
    if (c == null) throw new IllegalStateException("Device characteristic unavailable");
    byte[] value = json.getBytes(StandardCharsets.UTF_8);
    boolean accepted;
    if (Build.VERSION.SDK_INT >= 33)
      accepted =
          gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
              == BluetoothStatusCodes.SUCCESS;
    else {
      c.setValue(value);
      c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      accepted = gatt.writeCharacteristic(c);
    }
    if (!accepted) throw new IllegalStateException("Bluetooth is busy; try again");
  }

  private void fail(String message) {
    disconnect();
    status.accept("BLE disconnected - " + message);
  }

  public void disconnect() {
    reconnect = false;
    cancelTimeout();
    if (retry != null) handler.removeCallbacks(retry);
    BluetoothGatt previous = gatt;
    gatt = null;
    phase = "IDLE";
    linkConnected = false;
    assembler.clear();
    if (previous != null) {
      try {
        previous.disconnect();
        previous.close();
      } catch (SecurityException ignored) {
      }
    }
    status.accept("BLE disconnected");
  }

  @Override
  public void close() {
    stopScan();
    disconnect();
    if (!closed) context.unregisterReceiver(bondReceiver);
    closed = true;
  }
}
