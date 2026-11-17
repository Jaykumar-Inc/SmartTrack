# ESP32 setup and bench procedure

Target: ESP32 DevKit V1, `esp32doit-devkit-v1` PlatformIO board, Espressif32 platform 6.10.0 / Arduino core 2.0.17. IMU: **LSM6DS3**, not MPU6050. Serial diagnostics: 115200 baud. GPS UART: 9600 baud. The firmware does not use GPIO16 or GPIO17.

| ESP32 | Connection |
|---|---|
| GPIO21 | I²C SDA: MAX30102, LSM6DS3, SSD1306 |
| GPIO22 | I²C SCL: MAX30102, LSM6DS3, SSD1306 |
| GPIO26 (RX) | NEO-6M TX |
| GPIO27 (TX) | NEO-6M RX, optional; leave unconnected if unused |
| GND | Common signal ground |

Expected addresses: MAX30102 0x57, GY-LSM6DS3 0x6B, SSD1306 0x3C. Check that the IMU's address strap is correct; some modules default to 0x6A. Verify SDA/SCL pull-ups are to 3.3 V and not to a 5 V supply. Avoid excessive parallel pull-ups. Use short wiring and a supply that tolerates ESP32 radio current peaks.

Breakout VCC labels are not a voltage specification. Identify the exact board and inspect its schematic before powering it. The bare MAX30102 uses a 1.8 V sensor rail and a separate LED rail (3.3 V nominal); do not connect 3.3/5 V directly to its bare sensor VDD. A breakout may provide regulators and level conversion, but this must be verified from that breakout's documentation. [Analog Devices datasheet](https://www.analog.com/media/en/technical-documentation/data-sheets/max30102.pdf).

The bare NEO-6M supply range is 2.7–3.6 V. A carrier board may accept a higher input through its regulator; do not assume it does. Verify its TX logic level before connecting to the ESP32. [u-blox NEO-6 datasheet](https://content.u-blox.com/sites/default/files/products/documents/NEO-6_DataSheet_%28GPS.G6-HW-09005%29.pdf).

Use an LSM6DS3 and SSD1306 breakout explicitly compatible with 3.3 V power/signals, following their carrier-board documentation. ESP32 GPIOs must stay within the ESP32 datasheet electrical limits; never assume 5 V logic tolerance. [Espressif datasheet](https://www.espressif.com/sites/default/files/documentation/esp32_datasheet_en.pdf). USB/5 V board supply pins are different from GPIO and 3V3 pins.

## Build, upload and provision

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-firmware.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-firmware.ps1 -Upload -Port COM5
```

Create a device through an authenticated client. Connect to its serial port at 115200. Send one JSON line (example placeholders are not valid secrets):

```json
{"deviceId":"esp32-001","ssid":"YOUR_NETWORK","password":"YOUR_WIFI_PASSWORD","backend":"https://YOUR_BACKEND","token":"dev_YOUR_GENERATED_TOKEN","ca":"-----BEGIN CERTIFICATE-----\nYOUR_ROOT_CA\n-----END CERTIFICATE-----\n"}
```

Provisioning is saved in NVS and the device restarts. Backend addresses must include a scheme and must not end in `/`. HTTP may be used on a trusted isolated development LAN, with its security limitations understood; use the computer's LAN address, not 127.0.0.1. The API binds loopback by default; set `SMARTTRACK_BIND` to the intended LAN interface for device access. TLS needs the correct CA and synchronized time. Never use `setInsecure()` as a production shortcut. NTP/GPS synchronization may be required before the first TLS request.

Serial commands: `diagnostics` prints availability, GPS checksum counters, heap, queue/drop counts, clock source and configured OLED driver/size; `oledtest` shows a four-quadrant 128x64 pattern for 15 seconds; `calibrate` performs stationary gyro bias calibration. The OLED also shows the pattern for four seconds on boot. Check all four labels and the complete border before diagnosing page layout. The availability flag is library initialization status, not physical controller identification. Calibration cancels on excessive movement and persists only until reboot. Input is bounded and credentials are not echoed by firmware. Disable terminal local echo and keep serial logs private when provisioning.

## Sampling and experimental fall detector

MAX30102: firmware 1.1.1-dev uses paired, nonblocking red/IR FIFO reads; 100 Hz conversion, averaging four, about 25 records/second. The 100-sample window retains actual timestamps and advances by 25 records. ADC range is 8192 nA; bounded LED-current changes reset the window. Contact requires three IR samples above 50,000; readings clear below 35,000, with five low samples returning to idle. Both emitters sleep for 800 ms between 320 ms IR-only contact probes. Reject FIFO gaps, incorrect cadence, low/clipped channels and algorithm-invalid/out-of-range estimates. `pulse off/on` provides temporary full shutdown/resume; `pulsewindow` requests a private USB waveform. Thresholds require physical review; valid SpO2 was not obtained in this follow-up. See [pulse test evidence](PULSE_FIX_1_1_1.md). A missing/failed pulse sensor is not a zero reading.

IMU: 104 Hz sensor configuration, polled on 10 ms monotonic intervals, ±16 g and ±2000 °/s. Orientation uses low-pass-filtered acceleration. Standalone impact threshold: >3.5 g with a 3 s event cooldown. Fall state machine: <0.45 g arms FREEFALL; >2.8 g must follow 80–1200 ms later; enter MONITORING; require magnitude 0.8–1.2 g continuously for 2 s and orientation change >35°. Monitoring times out after 5 s. FALL emits once, then enters a 30 s cooldown. Unsigned scheduling deltas tolerate millisecond-counter rollover. Activity classification is heuristic and not a calibrated activity tracker.

GPS: parse UART continuously, monitor total characters and checksum failures; communication becomes stale after 3 s without characters; location becomes stale after 5 s. A talking receiver with no satellite lock is different from a disconnected receiver. Last known fix is diagnostic context; stale coordinates are not emitted as a current fix.

OLED: six 128×64 views rotate every 8 s; refresh once/second. Pairing PIN takes priority for 60 s after BLE connect. Clock stays visibly unsynchronized until a source succeeds. NTP > GPS > previously synchronized running system time; a paired phone is an optional fallback. Display zone is Asia/Kolkata. A power cycle cannot preserve valid time without new synchronization.

## Physical verification record

Current firmware is **1.1.1-dev**; see [latest pulse/LED evidence](PULSE_FIX_1_1_1.md). The actual iQOO 12 previously passed authenticated BLE, account sharing and concurrent PC USB tests on 1.1.0-dev. These transport interfaces remain unchanged. Run `Connect-ESP32-USB.cmd` for PC readings. USB commands `usbstream on`/`off` control a renewable 15-second lease. GPS is now communicating and has produced a receiver fix; older no-UART observations below are historical. Valid physiological estimates, OLED photographs and extended bench checks remain outstanding.

On 16 November 2026, the connected ESP32-D0WD-V3 revision 3.1 was flashed on COM5 and verified over authenticated BLE through the local backend and both application displays. Pulse/IMU initialization succeeded; no-contact pulse values remained unavailable; GPS UART characters remained zero. See `docs/CONNECTION_FIXES_1_0_2.md` for executed evidence and remaining limits. Complete the other items in `tests/HARDWARE_CHECKLIST.md`, including carrier models, wiring photograph, supply voltage, OLED photograph and extended bench behavior. Build-time static RAM/flash usage is not runtime heap or coexistence evidence. Serial `paircode` returns the OLED passkey to the physically connected USB operator; keep it private and never log/share it.
