# SmartTrack 1.0.2 connection fixes and executed tests

Verified on 16 November 2026, Asia/Kolkata. These results supersede the initial preview's statements that no physical board had been tested.

## Fixes

- Desktop and emulator clients now use port **8087**, matching the local backend launcher. Known old default addresses on port 8080 migrate to 8087. Physical Android devices ask for the laptop/USB address rather than silently using the emulator-only address.
- Login includes **Test connection**, useful network/HTTP error messages, email/password validation, duplicate-account guidance, and an explicit local HTTP explanation. Android sign-out waits until the token is removed before showing the signed-out screen.
- Android BLE waits for bonding, sequences MTU negotiation/service discovery/notification subscription, checks descriptor results, handles timeouts and stale callbacks, limits reconnect attempts, and resets partial frames between connections. Paired SmartTrack devices appear alongside scan results.
- NimBLE JSON characteristic writes now pass explicit bytes and lengths. The old `setValue(const char*)` call selected NimBLE 1.x's generic template and transmitted pointer bytes. A physical identity read exposed this bug. Identity, configuration, motion, GPS and alerts now use the corrected write helper.
- Firmware requires authentication and an active snapshot subscription before publishing. USB diagnostics expose link/authentication/subscription/MTU/frame counters. `paircode` returns the OLED passkey only to the physically connected serial operator; the test tools do not log it.
- The OLED uses the full 128x64 layout, with a startup quadrant pattern and `oledtest` command. Physical visibility of the entire panel still requires a photograph/operator check.
- Both apps clearly label **HARDWARE** versus **SIMULATED** data. Missing physiological readings remain unavailable.

## Start and connect

1. Double-click `Start-Backend.cmd`. The local API is **http://127.0.0.1:8087**. Wi-Fi access on port 8087 was explicitly approved and enabled; use the phone URL printed by the launcher and saved in `Phone-Backend-Address.txt`. At verification it was **http://<LAPTOP_LAN_IP>:8087**. Keep both devices on the same Wi-Fi and tap Test connection in the app. `Start-Backend.cmd -Restart` reloads the identified workspace backend; unrelated processes are never stopped.
2. Windows: the per-user installation was upgraded from 1.0.0 to 1.0.2. Its installed application JAR matches the tested build. **Windows App Control blocks the rebuilt unsigned native launcher.** An approved signing certificate or administrator allowlisting is required. No application-control setting was changed, and no alternate launch route was used after this block.
3. Android: install `dist/SmartTrack-Android-debug.apk` (**1.0.2, code 3**). For backend access over USB, enable USB debugging, accept the laptop prompt and double-click `Connect-Phone-USB.cmd`. Enter **http://127.0.0.1:8087** in the phone app and tap Test connection. USB must remain connected. `Connect-Phone-USB.cmd -Install` also updates the APK while preserving app data.
4. For phone BLE, open Devices, choose **Direct BLE**, discover the SmartTrack device and connect **inside SmartTrack**. Enter the OLED code when Android requests pairing. OS pairing alone does not subscribe to readings. This path works without an account/backend. Disconnect the laptop BLE monitor before connecting the phone; firmware permits one client.
5. For laptop BLE, double-click `Connect-ESP32-Bluetooth.cmd`. It monitors actual hardware in the console. To forward readings to your signed-in workspace, run `Connect-ESP32-Bluetooth.cmd --backend http://127.0.0.1:8087`; it asks for your email/password without logging them. Existing device ownership still applies. The physically connected USB board can supply the pairing code using `--usb-pin-port COM5`.

Wireless LAN access was initially blocked by automatic approval review. The user subsequently explicitly approved network exposure, including local HTTP on the Public Wi-Fi. The backend now listens beyond loopback on port 8087, and the administrator-approved firewall rule permits inbound TCP 8087 only from the local subnet and only for this workspace's Java executable. Other firewall rules and network profiles were preserved. Health, login and protected registry access passed from the laptop through both localhost and its Wi-Fi IP. A physical phone-to-laptop test across the campus access point is still separate; client isolation may depend on that network. USB forwarding remains available.

## Executed verification

| Check | Result | Evidence |
|---|---|---|
| Full Java reactor | 9 contract, 5 backend, 2 desktop tests passed | `.work/connection-fixes-maven-final.log` |
| Same integration suite on MySQL 8.0.46 | 5 backend tests passed | `.work/connection-fixes-mysql.log` |
| Desktop real login form | Connection test, validation, registration, logout, wrong password, sign-in, duplicate account passed | `.work/desktop-auth-smoke.log` |
| Installed desktop application code | Installed JAR hash equals tested JAR; same auth UI journey passed using installed classes | `.work/installed-desktop-auth-smoke.log` |
| Windows installation upgrade | MSI returned 0; installed version 1.0.2 | `.work/windows-1.0.2-upgrade.log` |
| Installed native launcher | **Blocked by Windows App Control** | Start-Process reported application-control block; no policy bypass attempted |
| Android build/JVM/lint | APK assembled; 6 JVM tests passed; lint has warnings and no errors | `.work/connection-fixes-android-final.log` |
| Emulator account + monitoring journeys | 2 instrumentation tests passed, 56.502 seconds | `.work/android-connection-fixes-emulator.log` |
| Final APK emulator account + actual hardware readback | 2 tests passed, 40.037 seconds; real ESP32 reading reached encrypted cache and displayed HARDWARE | `.work/android-hardware-emulator-test.log` |
| USB backend forwarding | Helper ran successfully against the emulator; hardware readback used forwarded `127.0.0.1:8087` | Same final emulator log and ADB reverse configuration |
| Python schemas and BLE frames | 10 tests passed; frame tests cover MTU 23/185, reverse order, conflicting fragments and bounds | `tests/test_contract.py`, `tests/test_ble_bridge.py` |
| ESP32 firmware upload | COM5, ESP32-D0WD-V3 revision 3.1; flash hash verified | `.work/esp32-connection-fixes-upload.log` |
| Firmware static size | 59,332 bytes RAM; 1,231,465 bytes flash | Same upload/build log |
| BLE advertisement | Laptop discovered SmartTrack's service using the Intel adapter | `.work/esp32-ble-scan.json` |
| Fresh Windows pairing and encrypted GATT | Authenticated pairing succeeded; interval read/write and clock write succeeded; original interval restored | `.work/esp32-ble-fresh-pairing-test.log` |
| Physical BLE to backend/MySQL | 13 actual snapshots forwarded, zero rejected fragments in fresh-pairing run | Same hardware log |
| Live hardware to JavaFX | All eight screens and settings-edit preservation passed; latest run forwarded 35 hardware snapshots, zero rejected fragments | `.work/desktop-hardware-smoke.log`, `.work/desktop-hardware-bridge.log` |

Screenshots: [Android actual hardware](ui/android-1.0.2-hardware-health.png), [desktop actual hardware](ui/desktop-1.0.2-hardware-overview.png), [desktop settings](ui/desktop-1.0.2-settings.png).

## Remaining physical limits

The board reports MAX30102 and LSM6DS3 initialization success and real near-static acceleration. The pulse sensor reports **NO_CONTACT**, so no valid pulse/oxygen measurement or physiological accuracy is claimed. GPS reports **zero UART characters**, not just a lack of satellite fix: verify receiver power/common ground, GPS TX to GPIO26 and 9600 baud. No real GPS location was fabricated. Wi-Fi credentials/backend destination were not provisioned; the verified radio path is BLE through the laptop bridge.

Android's physical BLE radio flow still needs a real phone test. The emulator validates accounts, API transport, cache and UI; it does not emulate the ESP32 radio. OLED pixel coverage, supply/electrical checks, fall/impact jig tests, long radio coexistence and clinical validation remain unverified. Native C++ host tests remain blocked by Windows App Control; they are not counted as passing. Clean-machine install/uninstall and production signing remain separate checks.

The hardware bridge used the private workspace test account in `.work/demo-session.json`. The user subsequently explicitly approved releasing only the test-created `esp32-c1f8a3c` registration and its 98 bench readings. That cleanup is complete, so the board can be registered to the user's own account. The 98 records remain backed up without credentials in `.work/esp32-bench-readings-backup.json`; `.work/esp32-registration-release.json` records the backup hash and release time. Other registrations/accounts were preserved. Tokens/passwords remain private and excluded from source and artifact bundles.

Follow-up evidence: `.work/wifi-access-verification.json` records successful localhost/Wi-Fi health, login and protected registry checks, including absence of the released test registration. `.work/phone-firewall-result.json` records the scoped firewall setup. No firmware or APK rebuild was required for these network/ownership changes.
