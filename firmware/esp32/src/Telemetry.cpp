#include "DeviceState.h"
#include <WiFi.h>
static void number(JsonObject o, const char* key, double value) {
  if (std::isfinite(value))
    o[key] = value;
  else
    o[key] = nullptr;
}
void serializeTelemetry(JsonDocument& d, uint32_t sequence, uint32_t now) {
  d["protocolVersion"] = 1;
  d["deviceId"] = deviceId;
  d["sessionId"] = sessionId;
  d["sequence"] = sequence;
  d["uptimeMs"] = now;
  d["source"] = "HARDWARE";
  String timestamp = utcTimestamp();
  if (timestamp.length())
    d["timestamp"] = timestamp;
  else
    d["timestamp"] = nullptr;
  d["timestampQuality"] = clockQuality();
  auto h = d["health"].to<JsonObject>();
  number(h, "heartRateBpm", state.healthValid ? state.bpm : NAN);
  number(h, "spo2Percent", state.healthValid ? state.spo2 : NAN);
  h["fingerDetected"] = state.finger;
  h["quality"] = !state.maxAvailable ? "UNAVAILABLE"
                 : !state.finger     ? "NO_CONTACT"
                 : state.healthValid ? "VALID"
                                     : "LOW_SIGNAL";
  auto m = d["motion"].to<JsonObject>();
  number(m, "axG", state.imuAvailable ? state.ax : NAN);
  number(m, "ayG", state.imuAvailable ? state.ay : NAN);
  number(m, "azG", state.imuAvailable ? state.az : NAN);
  number(m, "gxDps", state.imuAvailable ? state.gx : NAN);
  number(m, "gyDps", state.imuAvailable ? state.gy : NAN);
  number(m, "gzDps", state.imuAvailable ? state.gz : NAN);
  number(m, "temperatureC", state.imuAvailable ? state.temperature : NAN);
  number(m, "magnitudeG", state.imuAvailable ? state.magnitude : NAN);
  number(m, "pitchDeg", state.imuAvailable ? state.pitch : NAN);
  number(m, "rollDeg", state.imuAvailable ? state.roll : NAN);
  m["activity"] = state.imuAvailable ? state.activity : "UNKNOWN";
  m["fallState"] = state.fall.name();
  auto g = d["gps"].to<JsonObject>();
  g["communicating"] = state.gpsCommunicating;
  g["fix"] = state.gpsFix;
  if (state.gpsCommunicating)
    g["satellites"] = state.satellites;
  else
    g["satellites"] = nullptr;
  number(g, "latitude", state.gpsFix ? state.latitude : NAN);
  number(g, "longitude", state.gpsFix ? state.longitude : NAN);
  number(g, "altitudeM", state.gpsFix ? state.altitude : NAN);
  number(g, "speedKmh", state.gpsFix ? state.speed : NAN);
  if (state.lastFixAt.length())
    g["lastFixAt"] = state.lastFixAt;
  else
    g["lastFixAt"] = nullptr;
  g["characters"] = state.gpsCharacters;
  g["checksumFailures"] = state.gpsChecksumFailures;
  auto s = d["status"].to<JsonObject>();
  s["firmwareVersion"] = "1.1.1-dev";
  if (WiFi.isConnected())
    s["rssiDbm"] = WiFi.RSSI();
  else
    s["rssiDbm"] = nullptr;
  s["freeHeapBytes"] = ESP.getFreeHeap();
  s["wifiConnected"] = WiFi.isConnected();
  extern bool bleConnected();
  s["bleConnected"] = bleConnected();
  s["clockSource"] = clockQuality();
  auto a = d["alerts"].to<JsonArray>();
  if (state.impactEvent) {
    auto x = a.add<JsonObject>();
    x["kind"] = "IMPACT";
    x["severity"] = "WARNING";
  }
  if (state.fallEvent) {
    auto x = a.add<JsonObject>();
    x["kind"] = "EXPERIMENTAL_FALL";
    x["severity"] = "CRITICAL";
  }
}
