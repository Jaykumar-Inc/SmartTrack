#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include <atomic>
#include "FallDetector.h"
struct DeviceState {
  bool maxAvailable = false, imuAvailable = false, oledAvailable = false, finger = false,
       healthValid = false, gpsCommunicating = false, gpsFix = false;
  float bpm = NAN, spo2 = NAN, ax = NAN, ay = NAN, az = NAN, gx = NAN, gy = NAN, gz = NAN,
        temperature = NAN, magnitude = NAN, pitch = NAN, roll = NAN;
  double latitude = NAN, longitude = NAN, altitude = NAN, speed = NAN;
  int satellites = 0;
  uint32_t gpsCharacters = 0, gpsChecksumFailures = 0, lastGpsChar = 0, lastFixMs = 0,
           lastHealthMs = 0;
  String lastFixAt;
  const char* activity = "UNKNOWN";
  FallDetector fall;
  bool fallEvent = false, impactEvent = false;
  uint32_t lastImpact = 0;
  bool calibrating = false;
};
extern DeviceState state;
extern std::atomic<uint32_t> deliveryIntervalMs;
extern std::atomic<int> backendStatus;
extern std::atomic<uint32_t> droppedTelemetry;
extern String deviceId, sessionId;
void sensorsBegin();
void sensorsTick(uint32_t now);
void calibrateMotion();
void pulseDiagnostics();
void pulseCapture();
void pulseControl(bool enabled);
bool queuePulseCapture(JsonDocument& doc);
void clockBegin();
void clockTick();
String utcTimestamp();
const char* clockQuality();
void serializeTelemetry(JsonDocument& doc, uint32_t sequence, uint32_t now);
void displayBegin();
void displayTick(uint32_t now);
void displayPairingPin(uint32_t pin);
void displayTest(uint32_t now);
void transportBegin();
void transportTick(uint32_t now);
void queueTelemetry(const char* data);
void bleBegin();
void bleDiagnostics();
void blePairingCode();
void usbStreamControl(bool enabled, uint32_t now);
bool usbStreamActive();
void blePublish(const char* json, uint16_t frameId);
