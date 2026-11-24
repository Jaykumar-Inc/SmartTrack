# SmartTrack

IoT health, motion and internal chip temperature monitoring for Advanced Java Programming, SRM University-AP.

The ESP32 reads MAX30102 and GY-LSM6DS3, displays a touch-controlled watch interface, and sends readings over Wi-Fi to Spring Boot/MySQL. Android and JavaFX use the same account, readings and alerts. The LSM6DS3 supplies **internal chip temperature**, not body temperature or a clinical fever measurement.

## Run the existing setup

1. Power the ESP32. Run **START_SMARTTRACK.cmd** on this laptop. The saved **JAY** hotspot uses 2.4 GHz.
2. Connect your phone to JAY. Open **SHOW_LOGIN.cmd** for the private hotspot password and application login.
3. Install [SmartTrack-debug.apk](deliverables/SmartTrack-debug.apk) on Android. On Windows, extract [SmartTrack-Windows.zip](deliverables/SmartTrack-Windows.zip) fully and run SmartTrack.exe, or use the desktop opened by the starter.
4. Sign into both apps at **http://192.168.137.1:8080** with the same account. Check SHOW_LOGIN.cmd if the hotspot address changes.
5. Select the real wearable. Open **Temperature** for a chart, valid sample count, minimum/maximum in the displayed window and Celsius/Fahrenheit display. Export history from Health.

Keep the laptop awake and the server running. Phone monitoring requires this laptop server. The Windows ZIP bundles Java, while backend/database startup uses the project launcher and local prerequisites.

## Instructions and source

- [Full phone, desktop, firmware and connection instructions](docs/WIFI_SETUP.md)
- [Circuit connections](docs/HARDWARE.md)
- [Watch controls and temperature sampling](docs/SMARTWATCH.md)
- [Architecture](docs/ARCHITECTURE.md) and [API contract](docs/API.md)
- [Verification evidence and limits](docs/TEMPERATURE_VERIFICATION.md)
- Firmware: `esp32-firmware/SmartTrack/SmartTrack.ino`
- Java: `smarttrack-backend`, `smarttrack-desktop`, `smarttrack-android`
- University submission: `report/` and `presentation/`

Shared I2C stays on SDA21/SCL22. TTP223 outputs stay on GPIO32/33. The former UART receiver is disconnected and GPIO26/27 are unused. Temperature uses the existing IMU, with no extra sensor wiring or new firmware library.

Use **UPLOAD_ESP32.cmd** for the confirmed COM5 board. Close Arduino Serial Monitor before uploading. **CONFIGURE_WIFI.cmd** changes private network settings. **CHECK_SMARTTRACK.cmd** checks services and ingestion. **STOP_SMARTTRACK.cmd** retains all data.

Private credentials, provisioned firmware binaries, raw captures and runtime databases are excluded from source publishing. Old applied database migrations remain unchanged to preserve upgrade compatibility. Earlier location tables remain archived in an existing database but have no active API or UI. This prototype has no clinical validation or guaranteed emergency service.
