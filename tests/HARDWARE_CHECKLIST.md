# Physical acceptance checklist — outstanding gates

Partial bench evidence is recorded in `docs/CONNECT_AND_SHARE_1_1_0.md` and `docs/PULSE_FIX_1_1_1.md`: ESP32-D0WD-V3 revision 3.1, COM5, actual phone BLE/sharing, paired pulse acquisition, idle/full emitter shutdown and GPS communication/fix. Remaining gates below are not passing claims.

Record operator/date, ESP32 model, sensor/carrier revisions, power supply, wiring photo, firmware SHA-256 and phone/OS. Never substitute simulator data for the physical steps below.

| Check | Procedure | Required evidence | Status |
|---|---|---|---|
| Electrical | Verify carrier supply requirements and 3.3 V UART/I²C levels before wiring | Meter values, carrier schematics, wiring photo | Pending validation |
| Detection | Boot with all four I²C/UART modules; run diagnostics | Addresses/availability, UART character counters | Pending validation |
| Missing sensor | Disconnect one sensor, reboot and reconnect | Unavailable/null readings, no fabricated zeros, recovery log | Pending validation |
| Pulse acquisition | Gentle finger contact, removal and motion artifact | FIFO cadence, valid/invalid flags, comparison reference; no clinical claim | Paired cadence/no-loss, contact/removal and LED sleep/wake passed; valid SpO2 and reference comparison pending |
| IMU | Static axes, known orientation changes, stationary calibration | g, °/s, pitch/roll and calibration cancellation evidence | Pending validation |
| Fall/impact | Controlled non-person jig tests, ordinary activity and false-positive cases | State transitions, timestamps, cooldown; do not test falls on a person | Pending validation |
| GPS | Outdoor acquisition then antenna/receiver isolation | No-lock vs no-communication, checksums, stale-fix expiry | Pending validation |
| OLED | Photograph all six views and pairing PIN at 128×64 | No overflow, once-second clock, correct IST/date | Pending validation |
| Time | Boot offline, GPS sync, then NTP; remove sources; power cycle | Source priority and visibly unsynchronized reboot | Pending validation |
| Wi-Fi API | Provision real device, ingest HARDWARE snapshots | Device/session/sequence and MySQL rows, desktop display | Pending validation |
| BLE | Pair Android using displayed code; negotiate MTU 23 then 185 | Authenticated encryption, complete frames, no unpaired telemetry/config writes | Windows pairing/controls and actual phone live BLE/sharing passed on 1.1.0-dev; physical MTU fallback/bond lifecycle and 1.1.1 BLE regression pending |
| Coexistence | Stream BLE and Wi-Fi for ≥1 h | Minimum free heap, dropped frames, sample jitter, watchdog/reset count | Pending validation |
| Reconnect | Remove Wi-Fi/backend/Bluetooth separately, then restore | Cached/offline labels, automatic recovery, no duplicate rows | Pending validation |
| Offline sync | Cache BLE without network, reconnect and upload twice | Same sample IDs, exactly one persisted batch, preserved source | Pending validation |
| Power loss | Interrupt while samples queued; reboot | New session ID, acknowledged RAM-buffer loss, fresh clock sync | Pending validation |
| Android lifecycle | Rotate, navigate, background/foreground, deny/revoke Bluetooth permissions | No crash, no false monitoring guarantee, retained encrypted cache | Pending validation |
| Installer | Clean Windows VM, install/update/uninstall without JDK | Shortcuts, version, clean shutdown, no unrelated data loss | Pending validation |
