# SmartTrack - simple Wi-Fi setup

## How it works

```text
ESP32 + sensors -> JAY laptop hotspot -> Spring Boot -> MySQL
                                             |          |
                                         Android     Desktop
```

The watch sends real sensor readings to the laptop. Both apps use the same server, account, history, settings and alerts. There is no Bluetooth pairing or wireless COM-port selection.

## Daily use

1. Plug in/power the ESP32.
2. On the laptop, run **START_SMARTTRACK.cmd**. It starts the saved **JAY** hotspot in **2.4 GHz** mode, starts/reuses MySQL and the backend, starts server discovery, then opens the desktop. Windows idle hotspot shutdown is disabled so setup does not turn it off after five minutes without clients.
3. Connect the phone to the **JAY** Wi-Fi network. The saved hotspot password and application login are in private **SHOW_LOGIN.cmd** output.
4. Sign into both apps using **http://192.168.137.1:8080** and the same username/password. This is the actual address observed on this laptop. SHOW_LOGIN.cmd gives the current address if it changes.

Keep the laptop awake and the starter services running. Monitoring uses the local hotspot/server; it does not send readings to a cloud service. Windows may require an available network connection profile to enable its Mobile Hotspot; if automatic startup fails, enable it manually in Windows Settings and run the starter again.

## Android installation

1. Copy `deliverables/SmartTrack-debug.apk` to the iQOO 12 and open it.
2. Permit installation from the source you used when Android asks. This is a signed debug APK, version **3.1.0-temperature**, package `org.smarttrack.android`.
3. Open SmartTrack. The server field is prefilled with the laptop hotspot address; correct it from SHOW_LOGIN.cmd if necessary.
4. Enter the same desktop username and password and tap **Sign in**.
5. Select the wearable. Dashboard, Health, Activity, Temperature, Alerts and Settings all read the shared server. Health can export the displayed readings as CSV.

The app needs Internet/network access for the local HTTP server, and optional notification permission. It does not request Bluetooth, nearby-device scanning or phone location. Chip temperature comes from the existing GY-LSM6DS3 and needs no phone location permission. Passwords are not saved in app preferences. Monitoring/notifications are active-app features, not a background emergency service.

Existing server history is retained. Earlier phone-only history, if any, is not automatically uploaded to the shared database. Updating the APK does not intentionally erase the old phone database file.

## Windows desktop

Use START_SMARTTRACK.cmd for the configured project desktop and all local services. Alternatively, extract **deliverables/SmartTrack-Windows.zip** fully and run **SmartTrack.exe**. Java is included in that ZIP; the local backend/MySQL still runs through the existing project starter. The desktop client itself no longer needs Python or a Bluetooth driver.

The server URL can be edited on the sign-in screen. Use the same hotspot URL as Android. The Devices screen shows server/device status, with no pairing code or serial-port chooser. Settings and commands are saved on the server; the watch fetches them approximately every five seconds. A queued command should be checked against the subsequent real watch state.

## Firmware installation or network change

The confirmed USB programming port is **COM5** (CP210x ESP32). Close Arduino Serial Monitor before uploading.

- Normal upload: double-click **UPLOAD_ESP32.cmd**. The script writes/uses private saved Wi-Fi/device settings, builds `integrated-wifi`, uploads and verifies the flash.
- Change hotspot name/password: run **CONFIGURE_WIFI.cmd**, then START_SMARTTRACK.cmd and UPLOAD_ESP32.cmd. Keep Windows Mobile Hotspot in 2.4 GHz mode.
- A changed laptop IP does not require firmware editing/reflashing. The watch discovers the server using a nonce and a reply signed with its device key.
- Arduino IDE source is synchronized to the original `E:\COA\PROJECT\firmware\uno_led_display` folder. The canonical project source is `esp32-firmware/SmartTrack`.

The private header `smarttrack_config.h` contains Wi-Fi and device credentials. It is excluded from Git/source ZIPs. `config.example.h` contains empty placeholders. Do not share compiled privately provisioned firmware binaries. Earlier source/configuration backups are retained privately; no production database was reset.

## Connection indicators and recovery

The OLED header uses **W** for Wi-Fi and **S** for successful server uploads. Settings shows Wi-Fi/server status instead of a pairing code. Sensor acquisition, temperature sampling, touch handling and display timeout continue independently of HTTP delays. Reconnection is automatic while the hotspot/server returns.

A one-packet latest-value queue favors current readings over lossless outage storage. Retries preserve boot/sequence identity; duplicate ingestion is suppressed. Very old queued packets expire. No lossless-history or measured medical accuracy claim is made.

Server discovery uses **UDP 49001**; the shared API uses **TCP 8080**. The backend binds to the actual hotspot IP, rather than the university uplink address. MySQL stays on loopback **3308**; an unrelated service on 3306 is unchanged. Discovery replies are signed; local HTTP itself is not encrypted. Use the intended private laptop hotspot.

If Windows blocks access, right-click **ALLOW_WIFI.cmd -> Run as administrator**. Its script allows only the laptop hotspot address/local subnet for the API and discovery ports. It does not disable Windows Firewall. These additional rules were not needed for the successful live test on the current Windows configuration.

## Wiring and watch controls

| Signal | ESP32 connection |
|---|---|
| Shared I2C SDA | GPIO21 |
| Shared I2C SCL | GPIO22 |
| MAX30102 | I2C address 0x57 |
| GY-LSM6DS3 | I2C address 0x6B |
| SSD1306 128x64 | I2C address 0x3C |
| TTP223 OUT1 | GPIO32 |
| TTP223 OUT2 | GPIO33 |
| TTP223 VCC/GND | 3.3V/common GND |

Power modules according to the actual breakout board's rating; ESP32-facing logic is 3.3V. Keep every module's ground connected. SDA21/SCL22 and touch GPIO32/33 remain unchanged. Disconnect the old UART module completely; GPIO26/27 are unused. Temperature needs no additional wiring.

Touch 1 short: open/select; long: Home. Touch 2 short: next; long: previous. Both: display on/off. The default display timeout is 15 seconds; brightness/timeout/raise-to-wake remain configurable through watch Settings.

## Troubleshooting

| Symptom | Check |
|---|---|
| W is off | JAY hotspot on, saved password correct, 2.4 GHz band; run CONFIGURE_WIFI.cmd if changed |
| W on, S off | START_SMARTTRACK.cmd running, discovery process running, correct device key and hotspot firewall rules |
| Phone cannot sign in | Phone on JAY, server URL from SHOW_LOGIN.cmd, same app username/password as desktop |
| Historical/stale label | Latest data is old or server is unavailable; do not interpret it as a current measurement |
| NO_FINGER | Place a finger over MAX30102; invalid readings remain unavailable |
| IMU calibrating | Keep the board still and flat with +Z up |
| Old Bluetooth name remains in Windows | It is a cached pairing record from an earlier release; the new firmware has no Bluetooth connection feature |

Use CHECK_SMARTTRACK.cmd for services/ingestion status, READ_ESP32.cmd for private USB diagnostics, and STOP_SMARTTRACK.cmd to stop project services while retaining data. See [WIFI_VERIFICATION.md](WIFI_VERIFICATION.md) for actual results and remaining physical checks.

Windows hotspot timeout behavior is documented by Microsoft: https://learn.microsoft.com/en-us/uwp/api/windows.networking.networkoperators.networkoperatortetheringmanager.enablenoconnectionstimeout .


## Temperature features and connection diagnostics

Temperature has a dedicated screen in both apps and the watch. Both apps show min/max over their displayed valid history, a chart with gaps for unavailable samples, and C/F controls. CSV always exports Celsius, quality and, on Android, sample age. These are chip measurements, not body/fever measurements. Historical values remain marked historical when telemetry stops. The connection panel reports measured server response latency, time since success and consecutive failed requests. A fresh server response does not by itself establish fresh sensor data.

## Build on another laptop

This configured workspace contains tools under the parent .tools folder; these are intentionally excluded from Git. A fresh machine needs JDK21, Maven3.9+, Python3, MySQL8.x, Android SDK/Gradle8.13 for APK builds, and PlatformIO espressif32 6.12.0 for firmware. Build Java with `mvn -f pom.xml package`. Build Android with `gradle -p smarttrack-android assembleDebug lintDebug testDebugUnitTest` after setting JAVA_HOME and ANDROID_HOME. Build firmware with `pio run -d esp32-firmware -e integrated-wifi`. See pom.xml, build.gradle and platformio.ini for pinned dependencies. Do not expect the private local starter to contain tools/databases on a fresh clone. For manual backend launch set DB_URL, DB_USER, DB_PASSWORD, SMARTTRACK_USER, SMARTTRACK_PASSWORD, PORT and SERVER_ADDRESS, then run the backend JAR with simulation disabled. Create a MySQL database/user with rights on that database; Flyway creates/migrates tables. Register a real device using POST /api/devices, then provision its returned key and hotspot credentials privately in smarttrack_config.h. Never commit that header. The local launcher supports this existing Windows deployment and cached tools; adapt its tools paths before using it on another machine.


## Desktop automatic refresh

Desktop 3.1.1 refreshes readings and the Devices screen automatically while connected. The default interval is one second; Settings can change it from 1 to 30 seconds. The footer shows Auto refresh and the last successful update time. Refresh now remains an optional manual action. Disconnect pauses polling; Connect resumes it. Settings inputs remain intact while background refresh continues. A current server update does not make an old sensor measurement live.
