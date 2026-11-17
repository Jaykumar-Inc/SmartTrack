# SmartTrack 1.1.0 - verified PC and phone connections

This is the earlier connection baseline. The [firmware 1.1.1-dev pulse/LED follow-up](PULSE_FIX_1_1_1.md) supersedes the firmware version and sensor observations below: GPS now communicates/has a fix, LED shutdown/idle probing is verified, but valid SpO2 remains unresolved. Android remains 1.1.0.

Verified 16 November 2026, Asia/Kolkata. Android is **1.1.0, version code 4**; firmware is **1.1.0-dev**. The updated APK is installed on the user's actual iQOO 12 (Android API 36), and its SHA-256 matches the delivered build.

## Working setup

1. Start `Start-Backend.cmd` on the laptop. The phone URL is saved in `Phone-Backend-Address.txt`; at verification it was **http://<LAPTOP_LAN_IP>:8087**. Port 8080 belongs to another existing API and is not this project's current backend.
2. On the phone, use Devices > Set up backend connection. Enter the phone URL and sign in with your own account. The actual phone's identity/health check and authenticated registry returned successfully.
3. Turn on phone Bluetooth and choose **Connect nearby ESP32**. Accept pairing if requested. Three fresh readings from `esp32-c1f8a3c` were received on the actual phone. The board is linked to the account used on the phone, and real readings were successfully shared with the backend.
4. The phone was reopened after testing. Remembered BLE reconnect worked; the actual screen showed **HARDWARE / LIVE**, and sharing reported zero waiting readings. Manual Disconnect disables automatic reconnect until the user connects again.
5. For PC readings, double-click **Connect-ESP32-USB.cmd**. It detects the USB port, displays real measurements/availability, and leaves Bluetooth available. Five real USB readings were verified while the physical phone remained on Bluetooth. To share from the PC too, run `Connect-ESP32-USB.cmd --backend http://127.0.0.1:8087` and use the same account as on the phone.

The phone uses Wi-Fi for backend access and BLE for ESP32 readings. Its USB connection was used for development/testing; no USB backend forwarding was needed for the physical phone's verified Wi-Fi test. Keep the ESP32 powered. Monitoring is verified with SmartTrack open; a guaranteed background monitoring service is not implemented.

## Connection findings

The phone initially had version 1.0.2, no saved backend URL, and no account session. The correct URL was saved, the user signed in directly on the phone, and authenticated API access was verified without exporting credentials. Bluetooth was also off during the first physical BLE attempt; the user enabled it and the repeat passed.

The laptop and phone have different /22 Wi-Fi subnets. The original local-subnet firewall rule did not cover the phone IP. An additional administrator-approved rule now permits **only the specific phone IP <PHONE_LAN_IP>**, TCP 8087, and this workspace's Java executable. A direct phone HTTP probe already succeeded before that extra rule, so subnet mismatch alone is not proof of the original failure's cause. The final app-level Wi-Fi check and real account access both pass. Other firewall rules and network profiles were preserved.

If DHCP changes the phone IP and access fails, read its current IPv4 under Diagnostics and run `Enable-Phone-Access.cmd -PhoneAddress NEW_PHONE_IP` as administrator. If the laptop IP changes, use the newly printed URL in the phone. `Connect-Phone-USB.cmd` remains an optional alternative.

## Added features and fixes

- One-tap nearby connection, Bluetooth enable prompt, remembered-device reconnect, explicit signed-out guidance and a live reading preview.
- A Diagnostics page with BLE stage/MTU/record count, app version, network type/IP, clock/health availability and a bounded event journal. Copy reports exclude passwords, account tokens, pairing codes and measurement values.
- A public `/v1/info` marker identifies the correct backend. APK sign-in and the PC bridge check it before sending credentials. The unrelated server on 8080 is rejected with an actionable message. This is configuration checking, not a replacement for HTTPS.
- Reused HTTP connections for polling; identity, health and sign-in use the configured server consistently.
- BLE-to-backend linking under the signed-in account, immediate foreground sharing and visible waiting/sent status. Only readings for devices owned by that account are uploaded; unrelated cached devices no longer block the whole batch. Background sharing remains subject to Android scheduling.
- CSV export through Android's system document picker, with null measurements preserved and values escaped. A real file save and device-scoped content were tested using explicitly simulated fixtures in the emulator.
- Direct USB telemetry for the PC, independently of the phone's Bluetooth link. UART output runs in the delivery worker. The monitor renews a 15-second streaming lease; an abandoned stream stops automatically. Optional recordings require an explicit output path.
- Firmware and APK updates preserved the existing configuration/account. The test companion did not replace the user's account or clear the phone's measurement cache.

## Executed checks

| Check | Result | Local evidence |
|---|---|---|
| Java reactor | 9 contract, 6 backend and 2 desktop unit tests passed | `.work/server-identity-110-java.log` |
| Android build/unit/lint | Build succeeded; 8 JVM tests passed; lint has no errors | `.work/android-1.1.0-final-build.log` |
| Python schemas/USB/BLE | 14 tests passed, including the new identity contract | `tests/test_contract.py`, `test_usb_monitor.py`, `test_ble_bridge.py` |
| Emulator regression | 3 tests passed: accounts, eight screens/lifecycle/settings, sharing ownership, diagnostics privacy and real CSV save | `.work/android-110-connection-tests-final.log` |
| Real phone network/account | Wi-Fi backend ready; session present; authenticated registry HTTP 200 | `.work/physical-phone-signed-in-audit.log`, `.work/physical-phone-audit.json` |
| Real phone BLE and sharing | Discovery matched; 3 new hardware readings; firmware 1.1.0-dev; account sharing succeeded | `.work/physical-phone-ble-test.log`, `.work/physical-phone-ble-result.json` |
| Real phone reconnect/UI | Reopened app showed HARDWARE/LIVE and zero waiting readings; visually inspected | Private `.work/physical-phone-live.png` |
| Concurrent transport | 45 USB and 25 BLE readings; 25 overlapping records matched exactly; zero decoder rejects | `.work/usb-ble-110-summary.json` |
| PC USB with the actual phone on BLE | 5 hardware readings, zero rejects/drops | `.work/pc-usb-with-physical-phone.log` |
| USB lease expiry | 15 readings; stream stopped after the unrenewed lease | `.work/usb-110-lease-test.json` |
| Correct-server detection | Port 8080 rejected before credential entry | Executed PC bridge negative check; `/v1/info` contract/integration tests |
| ESP32 build/upload | Firmware hash verified on COM5; static RAM 59,340 bytes, flash 1,232,169 bytes | `.work/esp32-1.1.0-upload.log` |

Real phone evidence is retained privately under `.work`; credentials and actual phone screenshots are not included in the source ZIP or public documentation screenshots. Hardware-to-API tests used an isolated in-memory server until the user signed into the real phone; the main board registration now belongs to that signed-in account and is preserved.

## Remaining limits

The live phone and USB monitor reported **NO_CONTACT** for pulse/oxygen, so no valid physiological estimate is claimed. Place a finger on the MAX30102 and allow a stable acquisition window for estimates. GPS still reports no receiver bytes; verify power, ground, receiver TX to ESP32 GPIO26, and 9600 baud. OLED pixel coverage, clinical accuracy, fall jig behavior, extended coexistence/soak and guaranteed background operation remain unverified.

The Windows native launcher is still blocked by **Smart App Control**. It has no individual-app approval option; trusted CA-issued code signing is required while that protection remains enabled. No suitable current-user signing certificate is available. The PC USB monitor is verified, but native Windows launcher execution is not claimed. Production Android signing and clean-machine distribution remain separate release work.
