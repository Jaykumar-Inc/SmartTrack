#include "DeviceState.h"
#include <Adafruit_SSD1306.h>
#include <WiFi.h>
#include <time.h>
static Adafruit_SSD1306 oled(128, 64, &Wire, -1);
static uint32_t lastDraw = 0, testStarted = 0, testDuration = 0;
static bool testing = false;
static std::atomic<uint32_t> pairingPin{0}, pairingShownAt{0};
static void testPattern() {
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.drawRect(0, 0, 128, 64, SSD1306_WHITE);
  oled.drawFastVLine(63, 0, 64, SSD1306_WHITE);
  oled.drawFastHLine(0, 31, 128, SSD1306_WHITE);
  oled.setCursor(8, 8);
  oled.print("TOP LEFT");
  oled.setCursor(68, 8);
  oled.print("TOP RIGHT");
  oled.setCursor(8, 40);
  oled.print("BOTTOM");
  oled.setCursor(8, 50);
  oled.print("LEFT");
  oled.setCursor(68, 40);
  oled.print("BOTTOM");
  oled.setCursor(68, 50);
  oled.print("RIGHT");
  oled.display();
}
void displayTest(uint32_t now) {
  if (!state.oledAvailable) {
    Serial.println("OLED unavailable; restart after checking wiring");
    return;
  }
  testStarted = now;
  testDuration = 15000;
  testing = true;
  lastDraw = testStarted - 1000;
  Serial.println("OLED test for 15 seconds: expect four labels and a complete outer border");
}
void displayBegin() {
  state.oledAvailable = oled.begin(SSD1306_SWITCHCAPVCC, 0x3C);
  if (state.oledAvailable) {
    oled.clearDisplay();
    oled.setTextSize(1);
    oled.setTextColor(SSD1306_WHITE);
    oled.setTextWrap(false);
    testStarted = millis();
    testDuration = 4000;
    testing = true;
    testPattern();
    Serial.println("OLED: SSD1306 128x64 at 0x3C; four-quadrant test for 4 seconds");
  }
}
void displayPairingPin(uint32_t pin) {
  pairingPin = pin;
  pairingShownAt = millis();
}
static void value(const char* label, float v, const char* unit) {
  oled.print(label);
  if (std::isfinite(v))
    oled.print(v, 1);
  else
    oled.print("--");
  oled.println(unit);
}
static void row(int y, const char* text) {
  oled.setCursor(0, y);
  oled.print(text);
}
static void header(const char* title) {
  row(0, title);
  oled.drawFastHLine(0, 10, 128, SSD1306_WHITE);
  oled.setCursor(0, 14);
}
static void footer(int page) {
  oled.drawFastHLine(0, 54, 128, SSD1306_WHITE);
  row(56, WiFi.isConnected() ? "WiFi ON" : "WiFi OFF");
  oled.setCursor(104, 56);
  oled.print(page + 1);
  oled.print("/6");
}
void displayTick(uint32_t now) {
  if (!state.oledAvailable || now - lastDraw < 1000) return;
  lastDraw = now;
  oled.clearDisplay();
  oled.setCursor(0, 0);
  oled.setTextSize(1);
  if (pairingPin && now - pairingShownAt < 60000) {
    header("BLE PAIRING");
    row(15, "Enter code on phone:");
    oled.setCursor(22, 29);
    oled.setTextSize(2);
    char code[8];
    snprintf(code, sizeof(code), "%06lu", (unsigned long)pairingPin);
    oled.println(code);
    oled.setTextSize(1);
    row(56, "Confirm on your phone");
    oled.display();
    return;
  }
  if (testing && now - testStarted < testDuration) {
    testPattern();
    return;
  }
  testing = false;
  int page = (now / 8000) % 6;
  switch (page) {
    case 0: {
      header("SMARTTRACK / CLOCK");
      if (String(clockQuality()) == "UNSYNCHRONIZED") {
        row(17, "Time not synchronized");
        row(33, "Waiting for GPS / NTP");
      } else {
        time_t t = time(nullptr);
        struct tm local;
        localtime_r(&t, &local);
        char line[24];
        strftime(line, sizeof(line), "%H:%M:%S", &local);
        oled.setCursor(16, 15);
        oled.setTextSize(2);
        oled.print(line);
        oled.setTextSize(1);
        strftime(line, sizeof(line), "%d %b %Y  IST", &local);
        row(35, line);
        row(45, clockQuality());
      }
      break;
    }
    case 1:
      header("HEALTH / EXPERIMENTAL");
      oled.setCursor(0, 15);
      value("BPM  ", state.healthValid ? state.bpm : NAN, "");
      oled.setCursor(0, 29);
      value("SpO2 ", state.healthValid ? state.spo2 : NAN, " %");
      row(43, !state.maxAvailable ? "Sensor unavailable"
              : !state.finger     ? "Place finger gently"
              : state.healthValid ? "Signal valid"
                                  : "Acquiring / low signal");
      break;
    case 2:
      header("MOTION");
      oled.setCursor(54, 0);
      oled.print(state.activity);
      oled.setCursor(0, 14);
      value("Accel ", state.magnitude, " g");
      oled.setCursor(0, 24);
      value("Pitch ", state.pitch, " deg");
      oled.setCursor(0, 34);
      value("Roll  ", state.roll, " deg");
      row(44, state.calibrating ? "Calibrating..." : state.fall.name());
      break;
    case 3:
      header("GPS");
      row(14, !state.gpsCommunicating ? "No receiver data"
              : state.gpsFix          ? "Satellite fix"
                                      : "Searching for fix");
      if (state.gpsFix) {
        oled.setCursor(0, 24);
        oled.println(state.latitude, 5);
        oled.setCursor(0, 34);
        oled.println(state.longitude, 5);
      }
      oled.setCursor(0, 44);
      oled.print("Satellites ");
      oled.println(state.satellites);
      break;
    case 4:
      header("ALERTS / EXPERIMENTAL");
      row(14, state.fall.name());
      row(24, state.impactEvent ? "Impact detected" : "No new impact");
      row(34, "Review events in app");
      row(44, "Ack stored by backend");
      break;
    case 5:
      header("SYSTEM 1.1.1-dev");
      extern bool bleConnected();
      oled.print("BLE ");
      oled.print(bleConnected() ? "ON" : "OFF");
      oled.print("  API ");
      oled.print(backendStatus);
      oled.setCursor(0, 24);
      oled.print("Clock ");
      oled.print(clockQuality());
      oled.setCursor(0, 34);
      oled.print("Up ");
      oled.print(now / 1000);
      oled.print("s");
      oled.setCursor(0, 44);
      oled.print("Dropped ");
      oled.println(droppedTelemetry);
      break;
  }
  footer(page);
  oled.display();
}
