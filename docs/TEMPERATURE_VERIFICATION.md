# Temperature release verification

Recorded on 24 November 2026 against the current source and real COM5 DOIT ESP32 DevKit V1.

| Check | Result |
|---|---|
| Maven backend | 9 tests passed; backend JAR packaged |
| Maven desktop | 2 tests passed; desktop JAR packaged |
| Android | 3.1.0-temperature, code 5; APK assembled, 5 JVM tests passed; lint 0 errors/14 warnings |
| Local provisioning/discovery | 10 tests passed |
| OLED renderer | 41 layouts passed, including temperature/unavailable/details |
| Temperature helper | Signed conversion, range, disconnection, expiry and millis rollover passed |
| Actual board | Compiled esp32doit-devkit-v1, successful flash/hash verification on COM5 |
| Shared clients | Production Android HTTP and JavaFX API clients read the same real record; WebSocket update received |
| Desktop UI | Seven actual view events, return to Dashboard and logout passed |
| MySQL | Flyway V4 applied on existing project MySQL; temperature rows persisted |
| Recovery | Backend restart with JAY on automatically recovered fresh real uploads |
| Final upload | 20 JSON packets; both sensors OK; temperature GOOD at 25.375 C; Wi-Fi/server OK; fresh real backend reading |
| Report | 14 A4 pages from Word, all pages rendered and reviewed |
| Presentation | 15 editable slides, rendered through installed PowerPoint and reviewed |

The first 15 second Serial capture produced 14 JSON packets, GOOD chip temperature at 24.0 C, motion CALIBRATING/MOVE_OR_NOT_FLAT and unavailable optical measurements. Actual desktop screenshots show another real moment at 24.1 C. During later checks both sensors became unavailable; firmware/backend correctly used null temperature. Restoring the hotspot restored uploads. The repeated backend-only restart recovered automatically. The final re-upload and 20 second Serial check detected both sensors, GOOD temperature and NO_FINGER optical status. Motion still required stable flat +Z-up calibration. These are separate observations, not an accuracy or clinical study.

The latest post-upload check is in tests/temperature-final-upload-results.json. tests/temperature-hardware-results.json records the controlled backend test and its earlier sensor state. Raw serial captures, app passwords, Wi-Fi/device secrets and firmware binaries stay private. Android physical installation/visual behavior, photographed full OLED panel, reference accuracy, deliberate sensor unplug trials and battery endurance remain unverified.

Original source and documentation backups are outside the published project under the workspace .tools folder. Applied database migrations and archived location rows remain for data compatibility; active firmware, routes and client screens use temperature.
