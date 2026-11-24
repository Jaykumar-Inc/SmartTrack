#pragma once
#include <stdint.h>
#include <math.h>
// Original LSM6DS3 (WHO_AM_I 0x69): 16 LSB/degree C, 0 at 25 C.
// Internal die temperature, not a body or calibrated ambient measurement.
class TemperatureSample {
 public:
  float celsius = NAN;
  uint32_t sampledAt = 0;
  bool present = false;
  void accept(int16_t raw, uint32_t now) {
    float value = 25.0f + raw / 16.0f;
    present = isfinite(value) && value >= -40 && value <= 85;
    if (present) { celsius=value; sampledAt=now; }
  }
  void invalidate() { present=false; }
  bool valid(uint32_t now) const { return present && uint32_t(now-sampledAt)<3000; }
  const char* quality(uint32_t now) const { return !present?"UNAVAILABLE":valid(now)?"GOOD":"STALE"; }
};
