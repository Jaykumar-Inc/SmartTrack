#include "DeviceState.h"
#include <time.h>
#include <sys/time.h>
#include <esp_sntp.h>
static std::atomic<bool> ntpSynced{false}, gpsSynced{false}, phoneSynced{false};
void clockBegin() {
  sntp_set_time_sync_notification_cb([](struct timeval*) { ntpSynced = true; });
  configTzTime("IST-5:30", "pool.ntp.org", "time.google.com");
}
void clockTick() {}
const char* clockQuality() {
  time_t now = time(nullptr);
  if (now < 1704067200) return "UNSYNCHRONIZED";
  return ntpSynced ? "NTP" : gpsSynced ? "GPS" : phoneSynced ? "PHONE" : "SYSTEM";
}
String utcTimestamp() {
  if (String(clockQuality()) == "UNSYNCHRONIZED") return "";
  time_t now = time(nullptr);
  struct tm utc;
  gmtime_r(&now, &utc);
  char buf[25];
  strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &utc);
  return String(buf);
}
void synchronizeGps(const struct tm& gps) {
  if (ntpSynced) return;
  struct tm copy = gps;
  // mktime interprets Asia/Kolkata local time; compensate the fixed 05:30 offset.
  time_t epoch = mktime(&copy) + 19800;
  struct timeval tv = {epoch, 0};
  settimeofday(&tv, nullptr);
  gpsSynced = true;
}
bool synchronizePhone(uint64_t epoch) {
  if (epoch < 1704067200ULL || epoch > 4102444800ULL) return false;
  if (ntpSynced || gpsSynced) return true;
  struct timeval tv = {(time_t)epoch, 0};
  settimeofday(&tv, nullptr);
  phoneSynced = true;
  return true;
}
