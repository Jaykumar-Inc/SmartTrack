# SmartTrack ESP32 source

## Current version and PC connection

Firmware **1.1.1-dev** adds paired FIFO acquisition, anti-clipping current adjustment, actual window timestamps and idle emitter shutdown/probing. Valid SpO2 remains unverified; see `docs/PULSE_FIX_1_1_1.md` in the repository for actual tests and remaining limits. Android 1.1.0 requires no reinstall. Double-click `Connect-ESP32-USB.cmd` to display PC readings without holding Bluetooth; optional `--backend http://127.0.0.1:8087` shares through the same account used on the phone.

Serial pulse commands: `diagnostics` reads LED/mode registers and signal/cadence status; `pulse off` disables both emitters and retry until `pulse on` or reboot; `pulsewindow` requests one private 100-pair USB waveform through the delivery worker. The current configuration is red/IR, 100 Hz conversion, averaging four, 411 us pulse width and 8192 nA ADC range. When uncovered, both emitters sleep between short IR-only contact probes. During measurement the fast optical pulses can look steady; slow blinking would interrupt acquisition. Separate board power indicators are not controlled by the sensor driver.

USB commands: `usbstream on` renews a 15-second lease and emits `TELEMETRY ` followed by canonical JSON; `usbstream off` stops it. `clock UNIX_SECONDS` offers a USB time source with the same NTP/GPS priority rules as phone time. The monitor renews the lease every three seconds and stops it on exit. Output runs in the delivery worker, away from sensor sampling. The framing/schema and telemetry source remain v1/HARDWARE. USB and BLE can run together; BLE itself still supports one central client.

## OLED half-screen check

The driver is configured for **SSD1306 128x64 at 0x3C**. After reset, a four-quadrant test appears for four seconds. All four labels (TOP LEFT, TOP RIGHT, BOTTOM LEFT, BOTTOM RIGHT) and the outer border should be visible. Normal pages use rows through pixel 63, with Wi-Fi status and a page number at the bottom.

To repeat the test for 15 seconds, send `oledtest` followed by a newline over serial at 115200 baud. Sensor sampling and radio delivery continue during the test; a BLE pairing code takes display priority. The `diagnostics` command reports the configured OLED size and driver. Its availability flag reports successful library initialization, not identification of the physical controller.

If the bottom labels or border are missing, record a photo of the test and the exact controller/module model. Resolution alone does not identify the controller: a 128x64 SH1106 needs its own driver. Do not change the height to 32 for a confirmed 128x64 panel. If the test is complete but a normal page looks wrong, record that page for a layout correction.

This is the **editable Arduino C++ source**. `firmware.bin` is the compiled image and cannot be edited as source code.

Start in [`src/main.cpp`](src/main.cpp). The firmware is split into working modules:

| File | Responsibility |
|---|---|
| `src/Sensors.cpp` | MAX30102, LSM6DS3 and NEO-6M acquisition/processing |
| `src/Clock.cpp` | NTP/GPS/phone time synchronization |
| `src/Display.cpp` | Six OLED views and pairing PIN |
| `src/Transport.cpp` | Wi-Fi, authenticated backend delivery, serial provisioning |
| `src/Ble.cpp` | Paired/encrypted GATT and framed notifications |
| `src/Telemetry.cpp` | Canonical v1 JSON serialization |
| `include/FallDetector.h` | Hardware-independent experimental state machine |

Open this folder in VS Code with PlatformIO. The target is **ESP32 DevKit V1**, environment `esp32dev`. All required libraries are pinned in `platformio.ini`.

```powershell
python -m platformio run -e esp32dev
python -m platformio run -e esp32dev -t upload --upload-port COM5
python -m platformio device monitor --port COM5 --baud 115200
```

From the SmartTrack repository root, the equivalent build/upload scripts are:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-firmware.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-firmware.ps1 -Upload -Port COM5
```

Wiring: SDA **GPIO21**, SCL **GPIO22**, GPS TX → ESP32 RX **GPIO26**, optional ESP32 TX **GPIO27** → GPS RX. Do not use GPIO16/17. GPS baud: 9600. OLED: SSD1306 128×64 at 0x3C; MAX30102: 0x57; LSM6DS3: 0x6B. Verify the exact breakout's supply requirements and 3.3 V signal levels; never assume 5 V tolerance.

Provision by sending one JSON line over serial at 115200, replacing every placeholder:

```json
{"deviceId":"esp32-001","ssid":"YOUR_WIFI","password":"YOUR_WIFI_PASSWORD","backend":"http://YOUR_COMPUTER_LAN_IP:8087","token":"dev_YOUR_DEVICE_TOKEN"}
```

Register the same device ID in SmartTrack to obtain its device token. HTTP is for a trusted development LAN; production requires HTTPS and the `ca` certificate field. The backend must bind a reachable LAN interface for a physical ESP32 or phone; loopback addresses are only local to the computer. Provisioning persists and restarts the device. `diagnostics` reports sensor/connectivity status; `calibrate` starts stationary gyro calibration.

The Android app supports **Nearby BLE** monitoring without a backend: Devices → monitoring mode → Direct BLE → Discover. Enter the PIN displayed on the OLED when Android requests pairing.

Previous transport verification on 1.1.0-dev used the connected ESP32-D0WD-V3 revision 3.1 on COM5: authenticated Windows BLE, JSON identity, notifications, configuration/clock writes, actual Android live readings/account sharing and concurrent PC USB all passed. The latest 1.1.1-dev pulse follow-up now observes GPS communication and a receiver fix, correcting the earlier no-UART-data observation. The user confirmed full emitter shutdown turns the visible glow off, and automatic idle sleep/probes passed. Valid SpO2 still was not obtained. Health estimates and fall detection remain experimental. See `docs/PULSE_FIX_1_1_1.md`, `docs/HARDWARE.md` and `docs/BLE_PROTOCOL.md` in the main repository for details.

Additional USB diagnostics: `diagnostics` reports link/authentication/subscription/MTU/frame counters; `paircode` returns the six-digit OLED code only over the physically connected serial port. Treat that code as private. The Windows BLE monitor can use `--usb-pin-port COM5` to enter the code without logging it. Connect inside SmartTrack or the supplied monitor; pairing in OS settings alone does not start telemetry. Only one BLE client can connect at a time.
