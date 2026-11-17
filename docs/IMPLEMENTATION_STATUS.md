# Implementation status

Engineering preview, updated 16 November 2026 (Asia/Kolkata). Current evidence: [actual phone connection and sharing checks](CONNECT_AND_SHARE_1_1_0.md). Older baseline results remain in TEST_RESULTS.md.

| Component | Current verified scope | Remaining limits |
|---|---|---|
| Contracts | Java records/schema/OpenAPI; 9 Java and cross-language tests | Protocol evolution remains versioned |
| ESP32 | 1.1.1-dev flashed on COM5; paired FIFO/ADC/timestamp/LED fixes; real GPS communication/fix now observed; BLE/USB coexistence previously passed on 1.1.0-dev | No valid SpO2 obtained; see PULSE_FIX_1_1_1.md; OLED photograph, electrical, fall jig, Wi-Fi and soak checks pending |
| Backend | Accounts, ownership, ingestion/deduplication, history, alerts, sync, WebSocket and public identity; 6 integration tests pass on H2; previous 5-test suite also passed MySQL | Local backend; hosted TLS/account recovery/production review pending |
| Desktop | Connection test and real-form account journey; all eight screens display live actual hardware; installed JAR matches tested build | Installed 1.0.2 unsigned native launcher is blocked by Windows App Control |
| Android | 1.1.0 code 4 installed on actual iQOO 12; Wi-Fi sign-in, BLE live readings, sharing and remembered reconnect pass; 8 JVM tests/lint and 3 emulator regressions pass | Older physical Android, extended background/soak and production signing pending |
| Windows Bluetooth | New console monitor and optional authenticated backend bridge; fresh passkey pairing and encrypted controls tested | One BLE client; long reconnect/soak behavior pending |
| PC USB / phone network | USB monitor physically verified while phone BLE stays live; actual phone Wi-Fi/account access works; optional USB API helper available | DHCP changes may require updating URL or scoped phone firewall rule |
| Packaging | Versioned 1.1.0 debug APK, backend, firmware/source ZIP/checksums; existing Windows install upgraded to 1.0.2 | Native launcher blocked by Smart App Control; no per-app approval option; trusted CA-issued signing and clean VM checks pending |

The path **physical ESP32 -> laptop BLE -> backend -> MySQL -> JavaFX** is verified, with 35 snapshots and zero rejected fragments in the latest desktop run. The Android emulator displayed the actual ESP32 source through USB API forwarding. Pulse/oxygen values remained unavailable rather than fabricated because no finger contact was present. No real GPS fix or clinical accuracy is claimed.

The user explicitly approved Wi-Fi exposure and releasing the test registration. The backend supports localhost and Wi-Fi on port 8087. Firewall rules cover the workspace Java executable, TCP 8087, local subnet and the specific phone IP <PHONE_LAN_IP>. Direct phone HTTP already worked before the extra phone rule; that rule alone is not proof of the original failure's cause. Actual phone identity/health and authenticated API checks pass. The old test registration and 98 readings were released after backup; the board is now linked to the user's signed-in phone account and that registration is preserved. Current phone URL: see Phone-Backend-Address.txt. The unrelated existing API on 8080 is preserved and rejected by the new server identity check.
