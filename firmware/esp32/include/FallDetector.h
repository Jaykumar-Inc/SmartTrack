#pragma once
#include <cstdint>
#include <cmath>
// Experimental: not a safety or emergency detector. Unsigned deltas tolerate millis wrap.
class FallDetector {
 public:
  enum State { IDLE, FREEFALL, IMPACT, MONITORING, FALL, COOLDOWN };
  State state = IDLE;
  bool update(uint32_t now, float magnitude, float pitch, float roll) {
    bool event = false;
    if (!std::isfinite(magnitude) || !std::isfinite(pitch) || !std::isfinite(roll)) return false;
    switch (state) {
      case IDLE:
        if (magnitude < 0.45f) {
          state = FREEFALL;
          since = now;
          startPitch = pitch;
          startRoll = roll;
        }
        break;
      case FREEFALL:
        if (now - since > 1200) {
          state = IDLE;
          break;
        }
        if (magnitude > 2.8f && now - since >= 80) {
          state = IMPACT;
          since = now;
        }
        break;
      case IMPACT:
        state = MONITORING;
        since = now;
        stillSince = now;
        break;
      case MONITORING:
        if (magnitude < 0.8f || magnitude > 1.2f) stillSince = now;
        if (now - since > 5000) {
          state = COOLDOWN;
          since = now;
          break;
        }
        if (now - stillSince >= 2000 &&
            (angle(pitch, startPitch) > 35 || angle(roll, startRoll) > 35)) {
          state = FALL;
          since = now;
          event = true;
        }
        break;
      case FALL:
        state = COOLDOWN;
        since = now;
        break;
      case COOLDOWN:
        if (now - since >= 30000) state = IDLE;
        break;
    }
    return event;
  }
  const char* name() const {
    static const char* names[] = {"IDLE", "FREEFALL", "IMPACT", "MONITORING", "FALL", "COOLDOWN"};
    return names[state];
  }

 private:
  uint32_t since = 0, stillSince = 0;
  float startPitch = 0, startRoll = 0;
  static float angle(float a, float b) {
    float d = std::fmod(std::fabs(a - b), 360.0f);
    return d > 180 ? 360 - d : d;
  }
};
