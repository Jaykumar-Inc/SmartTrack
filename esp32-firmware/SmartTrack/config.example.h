#pragma once
// Sensors and OLED keep working while Wi-Fi/server are unavailable.
#ifndef OLED_HEIGHT
#define OLED_HEIGHT 64  // Set to 32 ONLY if the panel is physically 128x32.
#endif
// Private Wi-Fi and device authentication; the starter writes smarttrack_config.h.
#define DEVICE_ID ""
#define WIFI_SSID ""
#define WIFI_PASSWORD ""
#define DEVICE_KEY ""
#define FINGER_THRESHOLD 50000UL
#define MOTION_SENSITIVITY 1.0f
#define LOW_G_THRESHOLD 0.60f
#define IMPACT_THRESHOLD_G 2.50f
#define FALL_INACTIVITY_MS 2000UL
#define FALL_COOLDOWN_MS 20000UL
#define IMPACT_WINDOW_MS 1500UL
#define VERIFICATION_WINDOW_MS 6500UL
#define FALL_ALERT_HOLD_MS 10000UL
#define ROTATION_TRIGGER_DPS 180.0f
#define REQUIRED_ROTATION_DEGREES 45.0f
#define REQUIRED_ORIENTATION_DEGREES 35.0f

// Optional watch overrides; defaults are in WatchConfig.h and work with old private headers.
// #define WATCH_SCREEN_TIMEOUT_MS 15000UL // 0 = always on
// #define WATCH_BRIGHTNESS 128           // SSD1306 contrast 0..255
// #define WATCH_RAISE_TO_WAKE true
// #define WATCH_UTC_OFFSET_SECONDS 19800L // UTC+05:30; backend timestamps remain UTC
// #define WATCH_RAISE_FACE_SIGN 1.0f      // -1 if display normal is opposite IMU +Z
// #define WATCH_TOUCH_ACTIVE_HIGH true   // TTP223 must use momentary output
