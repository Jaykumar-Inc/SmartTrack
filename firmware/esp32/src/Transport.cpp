#include "DeviceState.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
String deviceId, sessionId;
std::atomic<uint32_t> deliveryIntervalMs{1000};
std::atomic<int> backendStatus{0};
std::atomic<uint32_t> droppedTelemetry{0};
static String ssid, password, backend, token, ca;
static Preferences config;
struct Packet {
  char bytes[2048];
};
static QueueHandle_t queue;
static String uuid() {
  uint8_t b[16];
  esp_fill_random(b, 16);
  b[6] = (b[6] & 15) | 64;
  b[8] = (b[8] & 63) | 128;
  char s[37];
  snprintf(s, sizeof(s), "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13],
           b[14], b[15]);
  return String(s);
}
static bool beginHttp(HTTPClient& http, WiFiClient& plain, WiFiClientSecure& tls,
                      const String& path) {
  if (backend.startsWith("https://")) {
    if (ca.isEmpty()) {
      backendStatus = -10;
      return false;
    }
    tls.setCACert(ca.c_str());
    return http.begin(tls, backend + path);
  }
  if (backend.startsWith("http://")) return http.begin(plain, backend + path);
  backendStatus = -11;
  return false;
}
static void worker(void*) {
  Packet pending;
  uint32_t lastConfig = 0;
  for (;;) {
    if (!WiFi.isConnected() || backend.isEmpty() || token.isEmpty()) {
      vTaskDelay(pdMS_TO_TICKS(500));
      continue;
    }
    if (xQueuePeek(queue, &pending, pdMS_TO_TICKS(200)) == pdTRUE) {
      HTTPClient http;
      WiFiClient plain;
      WiFiClientSecure tls;
      http.setTimeout(1500);
      http.setConnectTimeout(1500);
      if (beginHttp(http, plain, tls, "/v1/telemetry")) {
        http.addHeader("Authorization", "Bearer " + token);
        http.addHeader("Content-Type", "application/json");
        backendStatus = http.POST((uint8_t*)pending.bytes, strlen(pending.bytes));
        if ((backendStatus >= 200 && backendStatus < 300) ||
            (backendStatus >= 400 && backendStatus < 500 && backendStatus != 429))
          xQueueReceive(queue, &pending, 0);
        http.end();
      }
    }
    if (millis() - lastConfig > 60000) {
      lastConfig = millis();
      HTTPClient http;
      WiFiClient plain;
      WiFiClientSecure tls;
      http.setTimeout(1500);
      if (beginHttp(http, plain, tls, "/v1/devices/" + deviceId + "/configuration")) {
        http.addHeader("Authorization", "Bearer " + token);
        if (http.GET() == 200) {
          JsonDocument d;
          if (!deserializeJson(d, http.getString())) {
            uint32_t interval = d["telemetry_interval_ms"] | 1000;
            if (interval >= 1000 && interval <= 60000) deliveryIntervalMs = interval;
          }
        }
        http.end();
      }
    }
    vTaskDelay(pdMS_TO_TICKS(1000));
  }
}
void queueTelemetry(const char* data) {
  if (strlen(data) >= sizeof(Packet::bytes)) {
    droppedTelemetry++;
    return;
  }
  Packet p;
  strlcpy(p.bytes, data, sizeof(p.bytes));
  if (xQueueSend(queue, &p, 0) != pdTRUE) droppedTelemetry++;
}
void transportBegin() {
  config.begin("smarttrack", false);
  ssid = config.getString("ssid");
  password = config.getString("password");
  backend = config.getString("backend");
  token = config.getString("token");
  ca = config.getString("ca");
  deviceId = config.getString("deviceId");
  if (deviceId.isEmpty()) {
    deviceId = "esp32-" + String((uint32_t)ESP.getEfuseMac(), HEX);
    config.putString("deviceId", deviceId);
  }
  sessionId = uuid();
  queue = xQueueCreate(16, sizeof(Packet));
  if (!queue) {
    Serial.println("Fatal: cannot allocate transport queue");
    ESP.restart();
  }
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  if (ssid.length()) WiFi.begin(ssid.c_str(), password.c_str());
  xTaskCreatePinnedToCore(worker, "wifi-delivery", 12288, nullptr, 1, nullptr, 0);
}
void transportTick(uint32_t now) {
  static String line;
  static uint32_t retry = 0;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      if (line == "calibrate")
        calibrateMotion();
      else if (line == "oledtest")
        displayTest(now);
      else if (line == "paircode")
        blePairingCode();
      else if (line == "usbstream on") {
        usbStreamControl(true, now);
        Serial.println("USB stream enabled; renew within 15 seconds");
      } else if (line == "usbstream off") {
        usbStreamControl(false, now);
        Serial.println("USB stream disabled");
      } else if (line.startsWith("clock ")) {
        extern bool synchronizePhone(uint64_t);
        String epoch = line.substring(6);
        bool digits = epoch.length() >= 10 && epoch.length() <= 11;
        for (char ch : epoch) digits &= isDigit(ch);
        if (digits && synchronizePhone(strtoull(epoch.c_str(), nullptr, 10)))
          Serial.println("USB clock accepted");
        else
          Serial.println("Clock rejected: use Unix epoch seconds");
      }
      else if (line == "pulse off") {
        pulseControl(false);
      }
      else if (line == "pulse on") {
        pulseControl(true);
      }
      else if (line == "pulsewindow") {
        pulseCapture();
      }
      else if (line == "diagnostics") {
        Serial.printf(
            "heap=%u api=%d queue=%u dropped=%u clock=%s max=%d imu=%d gpsChars=%u "
            "gpsChecksums=%u oled=%d oledSize=128x64 oledDriver=SSD1306\n",
            ESP.getFreeHeap(), backendStatus.load(), uxQueueMessagesWaiting(queue),
            droppedTelemetry.load(), clockQuality(), state.maxAvailable, state.imuAvailable,
            state.gpsCharacters, state.gpsChecksumFailures, state.oledAvailable);
        Serial.printf("wifi=%d ip=%s backendConfigured=%d deviceId=%s\n", WiFi.isConnected(),
                      WiFi.localIP().toString().c_str(), !backend.isEmpty() && !token.isEmpty(),
                      deviceId.c_str());
        bleDiagnostics();
        Serial.printf("usbStream=%d\n", usbStreamActive());
        pulseDiagnostics();
      } else {
        JsonDocument d;
        if (!deserializeJson(d, line) && d["deviceId"].is<const char*>() &&
            d["ssid"].is<const char*>() && d["backend"].is<const char*>() &&
            d["token"].is<const char*>()) {
          String id = d["deviceId"].as<String>(), url = d["backend"].as<String>(),
                 secret = d["token"].as<String>();
          bool valid = id.length() >= 3 && id.length() <= 64;
          for (char ch : id) valid &= isalnum(ch) || ch == '_' || ch == '-';
          if (valid && (url.startsWith("http://") || url.startsWith("https://")) &&
              secret.startsWith("dev_") && d["ssid"].as<String>().length() <= 32) {
            config.putString("deviceId", id);
            config.putString("ssid", d["ssid"].as<String>());
            config.putString("password", d["password"] | "");
            config.putString("backend", url);
            config.putString("token", secret);
            config.putString("ca", d["ca"] | "");
            Serial.println("Provisioning saved; rebooting");
            delay(100);
            ESP.restart();
          } else
            Serial.println("Invalid provisioning values");
        } else
          if (line.length()) Serial.println("Commands: diagnostics, pulsewindow, pulse on/off, oledtest, paircode, calibrate, usbstream on/off, clock EPOCH, or provisioning JSON");
      }
      line = "";
    } else if (c != '\r') {
      if (line.length() < 4096)
        line += c;
      else {
        line = "";
        Serial.println("Serial input too long");
      }
    }
  }
  if (!WiFi.isConnected() && ssid.length() && now - retry > 15000) {
    retry = now;
    WiFi.reconnect();
  }
}
