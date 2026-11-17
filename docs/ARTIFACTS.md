# Current development artifacts

Generated and verified 16 November 2026. Checksums are in `dist/SHA256SUMS.txt`.

| Artifact | Path | Bytes |
|---|---|---|
| Windows installer 1.0.2 | `dist/SmartTrack-1.0.2.msi` | 43,440,968 |
| Windows app image | `dist/SmartTrack/SmartTrack.exe` | 433,152 |
| Android debug APK 1.1.0 (code 4) | `dist/SmartTrack-Android-1.1.0-debug.apk` | 6,952,297 |
| Android current alias (identical) | `dist/SmartTrack-Android-debug.apk` | 6,952,297 |
| ESP32 editable source | `dist/SmartTrack-ESP32-source.zip` | 20,834 |
| ESP32 firmware 1.1.1-dev | `dist/firmware/firmware.bin` | 1,242,096 |
| Backend preview (reports 1.1.0; legacy filename) | `dist/smarttrack-backend-1.0.0-preview.jar` | 66,523,275 |

The Windows MSI upgrade succeeded locally and installed code passes auth testing, but Smart App Control blocks the unsigned native launcher. It has no individual-app approval option; trusted CA-issued code signing is required while this protection remains enabled. The old 1.0.0 EXE in dist is retained as an older artifact; it does not contain the current connection fixes. PC USB monitoring is available through `Connect-ESP32-USB.cmd`.

The APK is a development/debug build, not a production-signed release. It is already installed on the actual iQOO 12; Wi-Fi sign-in, live BLE and sharing passed. APK SHA-256: `46f7ff333d6820d4e2251ce6c39467f9f528e90318e8a637a88eae2cd84e1189`. The source ZIP matches all 12 current firmware files and excludes build caches/tools. Firmware 1.1.1-dev was flashed on COM5; SHA-256: `c76baedfb385108ffd220e04b100c586b5cca5986aa892fcdd3e3117b1db050f`. See [latest pulse/LED test evidence and limits](PULSE_FIX_1_1_1.md) and [phone connection verification](CONNECT_AND_SHARE_1_1_0.md).
