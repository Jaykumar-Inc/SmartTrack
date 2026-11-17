# Cross-component verification

1. `scripts/build.ps1`: contract JUnit tests, Spring Boot integration tests with H2 and desktop freshness tests; packages Java artifacts.
2. `scripts/verify-mysql.ps1`: same backend HTTP/WebSocket journeys against isolated real MySQL, including Flyway migrations and cross-owner access denial.
3. `scripts/start-demo.ps1`: explicitly simulated producer → authenticated API → MySQL. Private generated session stored under `.work`, never committed.
4. `scripts/verify-desktop.ps1`: JavaFX app-level harness consumes real API/database records, asserts simulated provenance, traverses all eight screens and saves scene-rendered PNGs; checks settings edits survive updates. This is not OS automation.
5. Android `testDebugUnitTest`: shared canonical contract Gson round-trip and bounded fragment reassembly (out of order, duplicate, conflicting and expired fragments).
6. Android `connectedDebugAndroidTest`: native emulator backend journey with isolated simulator data; traverses all screens. Physical BLE is a separate gate.
7. PlatformIO `run -e esp32dev`: actual cross-compiled ESP32 binary. `test -e native`: hardware-independent deterministic C++ fall-detector tests. Native execution may be blocked by Windows application control; report this separately from compilation.
8. `HARDWARE_CHECKLIST.md`: required physical paths. Use the versioned evidence in `docs/CONNECT_AND_SHARE_1_1_0.md` and `docs/PULSE_FIX_1_1_1.md` to distinguish the verified transport baseline from outstanding sensor, electrical and extended bench checks. A pass on an earlier firmware version is not an automatic regression pass on a later build.

Tests use UTC instants and versioned canonical telemetry, not production data. A highest-sequence sync watermark is not proof that lower sequence numbers arrived. Test reports live under module `target` / `build` directories. `docs/TEST_RESULTS.md` records the latest actual outcomes.
