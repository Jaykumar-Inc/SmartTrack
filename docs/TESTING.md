# Test the temperature release

1. Build Java with `mvn -f pom.xml package`. Backend tests cover authentication, owner filtering, quality validation, deduplication, stale readings, database failure and temperature while motion calibrates.
2. Android: `gradle -p smarttrack-android assembleDebug lintDebug testDebugUnitTest`. JVM tests exercise production HTTP and CSV, but do not prove physical phone behavior.
3. Firmware: `pio run -d esp32-firmware -e integrated-wifi`. Native host tests cover MotionEngine, touch, OLED transfer and temperature conversion/freshness/rollover. The renderer checks text bounds on all pages.
4. Upload COM5 after closing Serial Monitor. Start laptop services. Confirm W and S on OLED, and a new real WIFI reading in the backend. Temperature should be available independently of calibration. Do not invent optical values when no finger or poor signal.
5. On the phone, install APK, join JAY, use shared server/account. Compare latest record and temperature with desktop. In Temperature change units, inspect chart/min/max, export history, stop/restart server and check historical/recovery states.
6. With power disconnected, remove the IMU connection, then power again: temperature should show unavailable, with null celsius. Restore power/wiring and confirm automatic recovery. Physical disconnection tests require actual handling; automated tests alone do not establish them.

Current outcomes are in TEMPERATURE_VERIFICATION.md. Do not reinterpret old release logs as current evidence.
