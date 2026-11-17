# Android UI update — 1.0.1

The native Android package is version **1.0.1, code 2**. Its shared telemetry protocol remains v1.

Changes:

- Compact paired health cards, with stacked cards on narrow screens or larger font settings.
- Friendly device names in the toolbar, and device IDs in a separate, single-line metadata row.
- Separate source/freshness badge and connection-age detail. Offline/delayed states use warning color.
- Density-aware chart labels and real sample traces, including gaps and an honest empty state.
- Consistent rounded panels, spacing, neutral actions and readable motion-axis rows.
- Fixed labels above outlined form fields; labels cannot overlap saved values.
- Keyboard-aware insets and navigation visibility.
- Polling updates status without reconstructing the screen; data refreshes preserve scroll position.
- More-menu cancellation retains the current selected tab; repeated opening works.
- Failed sign-in keeps the form available with an inline error.

Verification: the debug APK builds, all five existing JVM tests pass and Android lint passes with warnings/no errors. The native emulator journey passes across Home, Health, Motion, Location, Alerts, History, Settings and Devices. It also checks activity recreation, More-menu cancellation, preservation of settings text over polling, and keyboard dismissal. All visual fixtures are explicitly simulated.

Screenshots: [Home](ui/android-1.0.1-home.png), [Health](ui/android-1.0.1-health.png), [Settings](ui/android-1.0.1-settings.png), [Devices](ui/android-1.0.1-devices.png), [320 dp narrow layout](ui/android-1.0.1-narrow.png). Default and narrow layouts were inspected in the emulator.

Artifact: `dist/SmartTrack-Android-debug.apk`. The editable ESP32 firmware is independent of the APK: start at `firmware/esp32/src/main.cpp`, or download `dist/SmartTrack-ESP32-source.zip`. See the source module's README for build/upload instructions. Hardware/BLE validation is still separate from software/emulator verification.
