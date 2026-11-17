#include "DeviceState.h"
#include <NimBLEDevice.h>
static NimBLEServer* server;
static NimBLECharacteristic *snapshot, *motion, *gps, *alerts;
static std::atomic<uint16_t> mtu{23};
static uint32_t pin;
static std::atomic<bool> connected{false}, authenticated{false}, subscribed{false};
static std::atomic<uint32_t> sentFrames{0};
static constexpr const char* SERVICE = "7fd90000-9c24-4f64-a65e-8a08cc91f001";
static void setJson(NimBLECharacteristic* characteristic, const String& json) {
  // NimBLE 1.x's generic setValue<const char*> serializes the pointer itself.
  // Always provide the bytes and length explicitly for JSON text.
  characteristic->setValue(reinterpret_cast<const uint8_t*>(json.c_str()), json.length());
}
static String characteristic(int suffix) {
  char s[37];
  snprintf(s, sizeof(s), "7fd900%02x-9c24-4f64-a65e-8a08cc91f001", suffix);
  return String(s);
}
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, ble_gap_conn_desc* desc) override {
    connected = true;
    authenticated = false;
    subscribed = false;
    Serial.println("BLE link connected; waiting for authenticated pairing");
    displayPairingPin(pin);
    NimBLEDevice::startSecurity(desc->conn_handle);
  }
  void onDisconnect(NimBLEServer*) override {
    connected = false;
    authenticated = false;
    subscribed = false;
    Serial.println("BLE disconnected; advertising resumed");
    mtu = 23;
    NimBLEDevice::startAdvertising();
  }
  void onMTUChange(uint16_t m, ble_gap_conn_desc*) override { mtu = min((uint16_t)185, m); }
  void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
    authenticated = desc->sec_state.encrypted && desc->sec_state.authenticated;
    Serial.printf("BLE security encrypted=%d authenticated=%d\n", desc->sec_state.encrypted,
                  desc->sec_state.authenticated);
    if (!authenticated) server->disconnect(desc->conn_handle);
  }
};
class SnapshotCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic*, ble_gap_conn_desc*, uint16_t value) override {
    subscribed = (value & 1) != 0;
    Serial.printf("BLE snapshot notifications=%d\n", subscribed.load());
  }
};
class ConfigCallbacks : public NimBLECharacteristicCallbacks {
  void onRead(NimBLECharacteristic* c) override {
    String value = "{\"telemetryIntervalMs\":" + String(deliveryIntervalMs) + "}";
    setJson(c, value);
  }
  void onWrite(NimBLECharacteristic* c) override {
    JsonDocument d;
    auto bytes = c->getValue();
    if (!deserializeJson(d, bytes.data(), bytes.size())) {
      uint32_t interval = d["telemetryIntervalMs"] | 0;
      if (interval >= 1000 && interval <= 60000) deliveryIntervalMs = interval;
    }
  }
};
class TimeCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    JsonDocument d;
    auto bytes = c->getValue();
    if (!deserializeJson(d, bytes.data(), bytes.size())) {
      extern bool synchronizePhone(uint64_t);
      synchronizePhone(d["epochSeconds"] | 0ULL);
    }
  }
};
bool bleConnected() { return connected && authenticated && subscribed; }
void bleDiagnostics() {
  Serial.printf("bleLink=%d bleAuthenticated=%d bleSubscribed=%d bleMtu=%u bleFrames=%u\n",
                connected.load(), authenticated.load(), subscribed.load(), mtu.load(),
                sentFrames.load());
}
void blePairingCode() {
  // A local USB operator can retrieve the same code shown on the OLED. Never send it over radio.
  displayPairingPin(pin);
  Serial.printf("BLE_PAIR_CODE=%06lu\n", (unsigned long)pin);
}
void bleBegin() {
  pin = 100000 + esp_random() % 900000;
  NimBLEDevice::init(("SmartTrack " + deviceId).c_str());
  NimBLEDevice::setMTU(185);
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityPasskey(pin);
  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  auto service = server->createService(SERVICE);
  auto identity = service->createCharacteristic(characteristic(1).c_str(), NIMBLE_PROPERTY::READ);
  JsonDocument id;
  id["deviceId"] = deviceId;
  id["firmwareVersion"] = "1.1.1-dev";
  String encoded;
  serializeJson(id, encoded);
  setJson(identity, encoded);
  const uint32_t read =
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN;
  snapshot =
      service->createCharacteristic(characteristic(2).c_str(), read | NIMBLE_PROPERTY::NOTIFY);
  snapshot->setCallbacks(new SnapshotCallbacks());
  motion = service->createCharacteristic(characteristic(3).c_str(), read | NIMBLE_PROPERTY::NOTIFY);
  gps = service->createCharacteristic(characteristic(4).c_str(), read | NIMBLE_PROPERTY::NOTIFY);
  alerts = service->createCharacteristic(characteristic(5).c_str(), read | NIMBLE_PROPERTY::NOTIFY);
  auto config = service->createCharacteristic(
      characteristic(6).c_str(),
      read | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
  setJson(config, "{\"telemetryIntervalMs\":1000}");
  config->setCallbacks(new ConfigCallbacks());
  auto time = service->createCharacteristic(
      characteristic(7).c_str(),
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
  time->setCallbacks(new TimeCallbacks());
  service->start();
  auto advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE);
  advertising->setScanResponse(true);
  advertising->start();
}
void blePublish(const char* json, uint16_t frameId) {
  if (!connected || !authenticated || !subscribed) return;
  // A complete canonical snapshot is fragmented so Android receives atomic, transport-independent
  // records.
  size_t length = strlen(json), chunk = mtu - 3 - 8;
  uint16_t total = (length + chunk - 1) / chunk;
  uint8_t packet[185];
  for (uint16_t part = 0; part < total; part++) {
    if (!connected || !authenticated || !subscribed) return;
    size_t offset = part * chunk, n = min(chunk, length - offset);
    packet[0] = 1;
    packet[1] = 1;
    packet[2] = frameId & 255;
    packet[3] = frameId >> 8;
    packet[4] = part & 255;
    packet[5] = part >> 8;
    packet[6] = total & 255;
    packet[7] = total >> 8;
    memcpy(packet + 8, json + offset, n);
    snapshot->setValue(packet, n + 8);
    snapshot->notify();
    vTaskDelay(pdMS_TO_TICKS(4));
  }
  sentFrames++;
  JsonDocument d;
  if (deserializeJson(d, json)) return;
  String s;
  serializeJson(d["motion"], s);
  setJson(motion, s);
  s = "";
  serializeJson(d["gps"], s);
  setJson(gps, s);
  s = "";
  serializeJson(d["alerts"], s);
  setJson(alerts, s);
}
