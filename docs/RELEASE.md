# Build and distribution

Build from the root with Java 21 and Maven 3.9.11. Dependencies are version pinned by the root Spring Boot 3.5.7 parent, JavaFX 21.0.9, Android Gradle files and PlatformIO configuration. `.tools` is developer-local; source consumers install equivalent tools. Regenerate contracts with `python scripts/generate-contract.py` and `python scripts/generate-openapi.py` when intentionally changing the v1 contract; breaking wire changes require a new version.

## Windows

`scripts/package-windows.ps1` uses JDK 21 jpackage to create an app image with a bundled runtime. `-Type exe` creates a per-user installer with Start Menu integration, a desktop shortcut, version 1.0.2 metadata and a stable upgrade UUID. WiX 3.14.1 candle/light must be on PATH for JDK 21 EXE packaging. Packaging does not install the app or change an existing installation. `-Type msi` also produces `dist/SmartTrack-1.0.2.msi`. The current MSI was executed separately and upgraded the local installation successfully, but Windows App Control blocks its unsigned native launcher; approved signing or administrator allowlisting is required. Output is `dist/SmartTrack` / `dist/SmartTrack-1.0.2.exe`.

The classpath launcher delegates to the JavaFX Application. Runtime modules include JavaFX controls/graphics/base, networking, preferences, logging, desktop and required crypto support. The packaging script supplies Windows-classified JavaFX modules to jlink, and the runtime launches them as named modules. Validate on a clean Windows VM without a preinstalled JDK. Code signing, installer execution, upgrade/uninstall and unsigned-binary application-control behavior are separate release gates.

## Android

`scripts/build-android.ps1` runs `assembleDebug testDebugUnitTest lintDebug`. The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Min API: 26. Compile/target API: 36. Android Gradle Plugin 8.10.1, Gradle 8.13. The debug package permits local HTTP for a trusted development environment; release requires HTTPS.

For a signed release, set these only in your shell/CI secret store:

```text
SMARTTRACK_KEYSTORE=absolute path to your private keystore
SMARTTRACK_STORE_PASSWORD=private store password
SMARTTRACK_KEY_ALIAS=private alias
SMARTTRACK_KEY_PASSWORD=private key password
```

Then run `scripts/build-android.ps1 -Release`. Never generate and commit a production key, share it in chat, or reuse the debug key. Keep secure backups and document signing certificate ownership. No signed release is claimed without an authorized signing key.

The emulator backend test (`BackendJourneyTest`) creates isolated simulator-owned records, seeds the app's encrypted credentials and traverses every screen. It does not validate physical BLE, power behavior, notification delivery or vendor-specific background restrictions. Run with `gradle connectedDebugAndroidTest` and a simulator-enabled development API on host port 8087, accessible at `10.0.2.2:8087` from the emulator.

## ESP32

`scripts/build-firmware.ps1` wraps PlatformIO. Firmware upload is explicit with `-Upload -Port COMx`. Build products: `.pio/build/esp32dev/{firmware.bin,bootloader.bin,partitions.bin,firmware.elf}`. Upload with PlatformIO so all offsets/partitions are correct. `huge_app.csv` allocates a large application partition and does not support OTA. No OTA updater is advertised. Use serial provisioning after upload, then execute the hardware checklist.

## Evidence and artifact integrity

Record source revision, tool versions, build output, test reports and SHA-256 sums with each release candidate. Preserve hardware logs separately from simulated test artifacts. Do not publish a clinical accuracy, fall-safety or background-monitoring claim based on this preview. See `IMPLEMENTATION_STATUS.md`, `TEST_RESULTS.md` and `KNOWN_LIMITATIONS.md` for actual evidence.
