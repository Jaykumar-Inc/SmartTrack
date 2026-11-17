# Verification results

## Pulse follow-up - firmware 1.1.1-dev

See [FIFO, ADC, timing and LED fixes with actual bench results](PULSE_FIX_1_1_1.md). Build/upload hash verification and 14 Python checks pass. Actual paired sampling held about 24.9 Hz without sample loss during the finger probes, the red channel no longer clips, and actual window timestamps avoid cumulative drift. **Valid SpO2 remains unverified**: the physical optical signal is still rejected. GPS now communicates and has a receiver fix. Android stays at 1.1.0.

## Current verification - 1.1.0

See [actual phone connection and sharing verification](CONNECT_AND_SHARE_1_1_0.md): 17 Java tests, 8 Android JVM tests, 14 Python tests and 3 emulator regression tests pass. The user's actual iQOO 12 passes Wi-Fi backend/account checks, ESP32 discovery, live hardware readings and backend sharing. Remembered reconnect and concurrent PC USB monitoring were also verified. Pulse remains NO_CONTACT; GPS receives no UART bytes. Native Windows execution remains blocked by Smart App Control. Earlier records below are historical.

## Previous verification - 1.0.2

See [connection fixes and executed tests](CONNECTION_FIXES_1_0_2.md) for the current record: 16 Java tests, 6 Android JVM tests, 10 Python tests, emulator account/monitoring/hardware journeys, physical ESP32 flashing and authenticated BLE, actual hardware to all eight desktop screens, and successful MSI upgrade. The rebuilt native launcher is blocked by Windows App Control. Previous tables below are historical baseline results; their hardware/signing status is superseded by the current record.

## Phone UI follow-up — Android 1.0.1

On 16 November 2026, the revised debug APK (version code 2) was built and checked with `assembleDebug testDebugUnitTest lintDebug`. Five JVM tests pass; lint reports no errors. The final emulator run passes the complete eight-screen journey, recreation, menu cancellation, settings persistence during polling and keyboard dismissal. Final log: `.work/android-v101-delivered-ui.log` (`OK (1 test)`). Screens were visually reviewed, and overlapping Settings labels found during review were fixed before delivery. See `ANDROID_UI_1_0_1.md` and `docs/ui` for the final UI evidence.

The ESP32 PlatformIO build was verified again (`.work/esp32-rebuild.log`), and a clean, 12-file source ZIP was added. Firmware logic was not changed in this follow-up. Backend/desktop evidence below is from the initial delivery; those modules were not changed or unnecessarily retested for the phone UI patch. Physical hardware has not been flashed or verified.

## Initial platform verification

Date: **16 November 2026, Asia/Kolkata**. This record reports executed checks, not proposed acceptance criteria.

| Verification | Actual result | Evidence |
|---|---|---|
| Java 21 Maven reactor | **Build verified**; `verify` succeeded for contracts, backend and desktop | `.work/java-verify.log`; module `target` outputs |
| Canonical Java contract | **6 tests passed** | `shared-contracts/target/surefire-reports` |
| HTTP/database/WebSocket integration, H2 | **4 tests passed** | `backend/target/surefire-reports`; final Maven reactor run |
| Same integration suite, MySQL 8.0.46 | **4 tests passed** against workspace port 3317 | `.work/mysql-verify.log`; actual Flyway/MySQL connection reported in that log |
| Python JSON Schema / cross-language checks | **6 tests passed** | `tests/test_contract.py`, executed with jsonschema 4.25.1 |
| JavaFX freshness model | **1 test passed** | `desktop/target/surefire-reports` |
| JavaFX runtime journey | **Passed**: simulator/API/MySQL records reached the native UI; all eight screens rendered; streaming did not replace an edited settings form | `.work/desktop-smoke.log`, `.work/ui/*.png` |
| Desktop visual review | **Inspected** Overview, Health, Motion, Location, Alerts, Devices, Analytics and Settings scene renders | `.work/ui` (actual JavaFX snapshots, not mockups) |
| Packaged Windows runtime | **Launch verified**: `SmartTrack.exe` child process exposed the SmartTrack window and connected to API port 8087 | Process/window metadata and TCP connection observed during this run; named JavaFX modules bundled |
| Android debug APK | **Build verified**; APK assembled | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Android fragment reassembly | **4 tests passed** | `android/app/build/test-results/testDebugUnitTest` |
| Android canonical Gson contract | **1 test passed** | Same Android JVM test report directory |
| Android lint | **Passed with warnings, no errors** | `android/app/build/reports/lint-results-debug.html` |
| Android emulator backend journey | **1 instrumentation test passed**: isolated simulated ingestion, encrypted Room cache, source label, all eight screens, activity recreation | `.work/android-device-test.log`; `android/app/build/reports/androidTests/connected/debug` |
| Direct instrumentation repeat for retained visual fixture | **1 test passed**; native Home screen visually inspected with clearly simulated/offline measurements | `.work/android-direct-test.log`, `.work/android-screen.png` |
| ESP32 cross-compilation | **Build verified** | `.work/firmware-build.log`; `.pio/build/esp32dev/firmware.bin` |
| ESP32 static size | **59,316 bytes RAM (18.1%)**, **1,229,449 bytes flash (39.1% of selected application partition)** | Final PlatformIO size report; not runtime heap/coexistence measurements |
| C++ fall detector host tests | **Blocked at execution** by Windows Application Control (`WinError 4551`); PlatformIO reached its testing phase | Four deterministic scenarios are present in `firmware/esp32/test/test_fall`; none is claimed as executed/passed; the earlier native build cache was subsequently replaced by firmware builds |
| Windows app image | **Build verified** | `dist/SmartTrack/SmartTrack.exe`, bundled runtime |
| Windows EXE installer | **Build verified** using JDK 21 jpackage and WiX 3.14.1 | `.work/windows-installer.log`; `dist/SmartTrack-1.0.0.exe` |
| APK/firmware/backend collection and integrity | **Completed** | `dist/SHA256SUMS.txt` |
| Physical sensors, Wi-Fi/BLE radios, OLED, GPS | **Pending validation — no hardware evidence** | `tests/HARDWARE_CHECKLIST.md` |
| Signed Android release / public Windows signing | **Pending external signing credentials** | Signing configuration exists; no production key was invented |
| Clean-VM install/upgrade/uninstall | **Pending validation** | Packaging success is not an installer-execution test |

## Integration behavior covered

Tests exercise account creation/login, device registration and authentication, strict required/unknown fields, nullable validity rules, future timestamp rejection, owner isolation, device scope, token rotation, identical retry deduplication, conflicting-sequence rejection, received-time history, synchronization state, alert acknowledgement and configuration revision conflicts. The streaming test waits for an explicit `ready` subscription message and checks a post-commit reading.

The concurrent transport test sends eight identical snapshots simultaneously and requires exactly one new batch with seven duplicate acknowledgements. It exposed MySQL repeatable-read snapshot visibility after an earlier authorization query. Ingestion now explicitly uses READ COMMITTED with per-device locking; the test passes against actual MySQL and H2.

The emulator is **Medium_Phone_API_37.0 / Android 17**. AndroidX Test was updated to stable Espresso 3.7.0 / runner 1.7.0 to support the emulator's input APIs. A lifecycle-aware cache-loading latch replaced an immediate first-frame assertion. API 26 compatibility is checked by build/lint; a physical API 26 device has not been tested.

## Environment and limits

The workspace initially had Java 27 on PATH, cached Spring/JavaFX dependencies, Android SDKs and MySQL outside PATH. A workspace-local Java 21.0.13, Maven 3.9.11, Gradle 8.13 and PlatformIO 6.1.18 were prepared. Backend binds loopback at 8087; the isolated data directory is `.tools/mysql-data`, port 3317. Existing services on 8080, 3306 and 3308 were left in place. Git was initialized; one exact `safe.directory` entry for `E:/Smarttrack` handles the sandbox account's Git-directory ownership.

The Windows computer-use helper could not start (`helper_unknown_error: setup refresh had errors`). Therefore OS-input-driven desktop testing is not claimed. The JavaFX harness launches the actual application, navigates its real controls and captures its scene. The separately packaged native launcher was also observed running and connected. Windows application control blocked only the generated native test executable; no security policy was disabled to run it.

Remaining warnings include pinned-library update suggestions, English/localization lint warnings, a SparkFun/Wire `I2C_BUFFER_LENGTH` redefinition in upstream code, and Mockito's dynamic-agent notice during Spring testing. No `BUFFER_SIZE` collision was introduced. Fresh-machine tool bootstrap, long soak/retention behavior, account recovery/deletion, production security review and performance profiling remain separate gates.
