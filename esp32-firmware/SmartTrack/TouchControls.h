#pragma once
#include <stdint.h>

namespace smartwatch {
enum TouchEvent : uint8_t {
  TouchNone = 0, Touch1Short = 1, Touch1Long = 2, Touch2Short = 4,
  Touch2Long = 8, TouchBoth = 16, TouchBegin = 32, TouchIdle = 64
};

// Pure C++: TTP223 OUT is a digital signal, not the ESP32 touchRead peripheral.
// Short actions fire on release. Long/chord gestures suppress their short actions.
class TouchControls {
 public:
  TouchControls(uint32_t debounceMs = 35, uint32_t longMs = 650)
      : debounceMs_(debounceMs), longMs_(longMs) {}

  uint8_t update(bool first, bool second, uint32_t now) {
    uint8_t events = 0;
    bool wasHeld = held();
    const bool inputs[] = {first, second};
    bool released[2] = {};
    for (uint8_t i = 0; i < 2; ++i) {
      Button& b = buttons_[i];
      if (inputs[i] != b.raw) { b.raw = inputs[i]; b.changed = now; }
      if (b.stable != b.raw && uint32_t(now - b.changed) >= debounceMs_) {
        b.stable = b.raw;
        if (b.stable) { b.pressed = now; b.longSent = false; events |= TouchBegin; }
        else released[i] = true;
      }
    }
    // Chord takes precedence before either long or release event is considered.
    if (buttons_[0].stable && buttons_[1].stable && !chord_) {
      chord_ = true;
      buttons_[0].longSent = buttons_[1].longSent = true;
      events |= TouchBoth;
    }
    if (!chord_) {
      for (uint8_t i = 0; i < 2; ++i) {
        Button& b = buttons_[i];
        if (b.stable && !b.longSent && uint32_t(now - b.pressed) >= longMs_) {
          events |= i == 0 ? Touch1Long : Touch2Long;
          b.longSent = true;
        }
        if (released[i] && !b.longSent) {
          // Poll delays across release must not turn a long hold into a short tap.
          const bool isLong = uint32_t(now - b.pressed) >= longMs_;
          events |= i == 0 ? (isLong ? Touch1Long : Touch1Short)
                           : (isLong ? Touch2Long : Touch2Short);
        }
      }
    }
    if (!held() && wasHeld) { chord_ = false; events |= TouchIdle; }
    return events;
  }
  bool held() const { return buttons_[0].stable || buttons_[1].stable; }

 private:
  struct Button { bool raw = false, stable = false, longSent = false;
                  uint32_t changed = 0, pressed = 0; } buttons_[2];
  uint32_t debounceMs_, longMs_;
  bool chord_ = false;
};
} // namespace smartwatch
