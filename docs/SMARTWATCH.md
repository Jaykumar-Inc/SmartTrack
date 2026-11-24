# Watch firmware

Seven screens: Home, Heart Rate, Oxygen, Motion, Chip Temperature, Alerts and Settings. The shared 128x64 OLED uses connection badges W for Wi-Fi and S for server acceptance. Brightness, timeout and raise-to-wake remain configurable. Default timeout is 15 seconds. 128x32 also has a compact layout.

| Touch gesture | Action |
|---|---|
| Touch 1 short | Open/select; Temperature details show Fahrenheit |
| Touch 1 long | Return Home |
| Touch 2 short | Next screen |
| Touch 2 long | Previous screen |
| Both | Toggle display power |

TTP223 modules use active-high momentary outputs on GPIO32/33, 3.3V and common ground. Let them settle at startup without touching their electrodes. Debouncing and gesture timing use millis. An IMU gesture can wake the OLED without making network calls.

Temperature is sampled about once per second independently of motion calibration. Checked raw I2C reads avoid treating a failed read as 25 C. GOOD samples expire after 3 seconds. Both firmware and backend require missing/stale values to be null. Chip temperature is not body temperature.

MAX30102 acquisition keeps its 100-sample/25-sample sliding window and quality gates. Motion remains a 20ms schedule. FreeRTOS workers handle display, Serial and HTTP on the other core. A single pending HTTP sample keeps its boot/sequence ID for retries and expires after 5 seconds. This favors fresh data and is not a lossless outage recorder.

Upload with UPLOAD_ESP32.cmd after closing Serial Monitor. Keep the IMU still, flat and +Z up for calibration. Calibration failure suppresses motion values but does not suppress chip temperature. Serial C cancels a fall event, K requests calibration. Sensor recovery probes run every 3 seconds.
