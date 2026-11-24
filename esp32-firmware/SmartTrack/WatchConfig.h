#pragma once

// Include after the private configuration. Every option can be overridden there.
#ifndef WATCH_TOUCH1_PIN
#define WATCH_TOUCH1_PIN 32
#endif
#ifndef WATCH_TOUCH2_PIN
#define WATCH_TOUCH2_PIN 33
#endif
#ifndef WATCH_TOUCH_ACTIVE_HIGH
#define WATCH_TOUCH_ACTIVE_HIGH true
#endif
#ifndef WATCH_DEBOUNCE_MS
#define WATCH_DEBOUNCE_MS 35UL
#endif
#ifndef WATCH_LONG_PRESS_MS
#define WATCH_LONG_PRESS_MS 650UL
#endif
#ifndef WATCH_SCREEN_TIMEOUT_MS
#define WATCH_SCREEN_TIMEOUT_MS 15000UL
#endif
#ifndef WATCH_BRIGHTNESS
#define WATCH_BRIGHTNESS 128
#endif
#ifndef WATCH_RAISE_TO_WAKE
#define WATCH_RAISE_TO_WAKE true
#endif
#ifndef WATCH_UTC_OFFSET_SECONDS
#define WATCH_UTC_OFFSET_SECONDS 19800L // India UTC+05:30; telemetry stays UTC.
#endif
#ifndef WATCH_RAISE_FACE_SIGN
#define WATCH_RAISE_FACE_SIGN 1.0f // Use -1 if the display faces opposite IMU +Z.
#endif
#ifndef WATCH_RAISE_LOWER_Z
#define WATCH_RAISE_LOWER_Z 0.30f
#endif
#ifndef WATCH_RAISE_UP_Z
#define WATCH_RAISE_UP_Z 0.75f
#endif
#ifndef WATCH_FRAME_INTERVAL_MS
#define WATCH_FRAME_INTERVAL_MS 80UL // Dirty chunks only; actual rate is bus-limited.
#endif
#ifndef WATCH_TRANSITION_MS
#define WATCH_TRANSITION_MS 400UL
#endif

static_assert(WATCH_TOUCH1_PIN == 32 && WATCH_TOUCH2_PIN == 33,
              "SmartTrack watch touch wiring is GPIO32 and GPIO33");
static_assert(WATCH_DEBOUNCE_MS >= 10 && WATCH_LONG_PRESS_MS > WATCH_DEBOUNCE_MS,
              "Invalid touch timing");
static_assert(WATCH_BRIGHTNESS >= 0 && WATCH_BRIGHTNESS <= 255, "OLED contrast is 0..255");
static_assert(WATCH_RAISE_LOWER_Z < WATCH_RAISE_UP_Z, "Raise thresholds need hysteresis");
static_assert(WATCH_TRANSITION_MS > 0 && WATCH_TRANSITION_MS <= 1000, "Invalid transition length");
