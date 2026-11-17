# MAX30102 acquisition and LED follow-up

Firmware 1.1.1-dev, 16 November 2026 (Asia/Kolkata). Android stays at 1.1.0; the telemetry contract is unchanged, so no APK reinstall is needed.

## Faults found and corrected

- The sampling loop mixed queued FIFO access with `getIR()`/`getRed()`. Those convenience calls each wait for a new conversion; red and IR could therefore come from different times and block other sampling. Both channels now come from the same FIFO entry through the nonblocking FIFO getters. See [SparkFun's API documentation](https://learn.sparkfun.com/tutorials/max30105-particle-and-pulse-ox-sensor-hookup-guide/advanced-functions).
- On this physical board, the red ADC clipped at 262143 with finger contact and the old 4096 nA range. The range is now 8192 nA. Bounded, independent LED-current adjustments reduce clipping, and any current change resets the acquisition window. A clipped or invalid result is never presented as oxygen data.
- The new cadence guard initially advanced its start by a nominal second on each window. Real oscillator drift accumulated in that timestamp. The corrected implementation retains actual timestamps with the 100 paired samples, avoiding cumulative timing error.
- The red emitter is off without contact. Both emitters now shut down between 320 ms IR-only contact probes separated by 800 ms sleep. Once contact is detected, both channels run uninterrupted at 100 Hz with four-sample averaging, producing approximately 25 FIFO records/second. Slow blinking during acquisition would interrupt the paired waveform, so the active sensor can appear continuously illuminated. The [MAX30102 datasheet](https://www.analog.com/media/en/technical-documentation/data-sheets/MAX30102.pdf) describes its pulsed LED drivers and shutdown controls.
- Contact hysteresis clears readings as contact is lost. FIFO gaps reset the window; the four-slot SparkFun software ring cannot retain batches of four or more. Genuine no-data probes cause sensor retry instead of fabricated values.

## USB bench controls

At 115200 baud, `diagnostics` reports actual LED-current and mode registers, finger state, raw levels, sample/loss/window counters and rejection reason. `pulse off` sets both currents to zero, asserts hardware shutdown and disables automatic sensor retry until `pulse on` or reboot. It leaves GPS, motion and transport running. This setting is temporary and does not erase configuration.

`pulsewindow` requests one 100-pair raw waveform when a complete contact window is available. The bounded capture goes only over USB through the delivery worker; it is never placed in BLE/API telemetry. Raw optical captures are private diagnostic data. A separate board power indicator, if fitted, is not controlled by the MAX30102 LED-current registers.

## Executed evidence and limits

The ESP32 was built and flashed on COM5 with firmware hash verification. The 14 existing Python schema/transport checks passed, and real USB snapshots were validated against the unchanged canonical schema.

Before adding idle sleep, uncovered and removal probes each showed red current register zero, `NO_CONTACT`, null physiological values, about 24.9 paired records/second and zero lost samples during each probe. Finger probes no longer clipped either ADC channel. The timestamp correction held windows near 3.98 seconds after more than 130 calculations, with no cumulative drift and no sample loss during the final 20-second probe.

**No valid SpO2 estimate was obtained.** Multiple finger placements still produced algorithm-rejected optical waveforms despite correct FIFO cadence and non-clipped levels. This does not establish any physiological value or prove the module is defective. Contact, carrier-board supply/logic compatibility and module condition require physical review; a known-good module/reference comparison remains outstanding. The user reported visible glow even with the red-current register zero, so a full shutdown check is recorded separately below.

Private evidence: `.work/esp32-1.1.1-final-upload.log`, `.work/pulse-111-no-contact.json`, `.work/pulse-111-finger.json`, `.work/pulse-111-final-finger.json`, `.work/pulse-111-removal.json` and `.work/pulse-111-waveform.json`. Intermediate probes are identified separately from final idle/shutdown checks. No clinical accuracy or valid oxygen measurement is claimed.

Final shutdown test: `pulse off` returned red/IR current registers both zero and mode register 131 (`0x83`, shutdown bit set). **The user confirmed the light went off.** Sensing was then re-enabled. In the final 12-second automatic-idle probe, all 12 red-current checks were zero; hardware sleep and IR-only probe modes were both observed; all 12 canonical readings were `NO_CONTACT` with null physiological values; no samples were lost. Private evidence: `.work/pulse-111-full-shutdown.json` and `.work/pulse-111-final-idle.json`. The idle average sample rate is intentionally lower than 25 Hz because the sensor sleeps between contact probes.

Final automatic-wake test: finger contact restored uninterrupted sampling at 24.85 records/second, with zero new lost samples and valid canonical USB frames. Windows stayed near 3.97 seconds. This verifies wake and timing behavior; all physiological results remained `LOW_SIGNAL`, so SpO2 is still unresolved. Evidence: `.work/pulse-111-final-wake.json`. Final firmware uses 59,820 bytes static RAM and 1,235,521 bytes flash; the flashable binary is 1,242,096 bytes. Its SHA-256 is `c76baedfb385108ffd220e04b100c586b5cca5986aa892fcdd3e3117b1db050f`.

A final Windows BLE regression attempt could not run because the laptop Bluetooth radio was powered off (`.work/pulse-111-ble-regression.log`). No BLE pass on 1.1.1-dev is claimed. The canonical schema/real USB checks passed; the APK is unchanged. Earlier phone/Windows BLE verification remains the 1.1.0-dev baseline.

GPS communication and a real receiver fix were present during this follow-up; the older no-UART-data observation is historical. Extended GPS accuracy/stale-fix bench checks remain pending.

## Wiring confirmation and reset recovery

The user subsequently confirmed VIN on ESP32 3V3, common ground, SDA GPIO21, SCL GPIO22 and RD/IRD unconnected. This is a reported connection check; the supplied image is a module pinout, not a photograph of the actual connected carrier. Its regulator output and LED rail have not been measured.

COM5 initially remained enumerated while both serial diagnostics and an esptool identification attempt received no bytes. After the user unplugged/reconnected USB and pressed EN/RESET, communication recovered: 6,848 bytes and six canonical hardware snapshots were received during a six-second check. Firmware remained 1.1.1-dev; the pulse sensor was detected, no finger was present, no lost samples were reported, and both IR-probe and emitter-shutdown states were observed. No firmware was rewritten or configuration erased in this recovery. Private evidence: `.work/pulse-111-after-manual-reset.json`.

This recovery confirms current communication and idle behavior, not valid SpO2. The four-wire connection provides no analog measurement of the carrier's supply rails; further supply validation requires a meter and identification of the actual carrier board.

## External 3.33 V supply follow-up

The user measured a steady 3.25 V at the module input with and without finger contact, then reported replacing that input source with a buck converter set to 3.33 V. They selected sensor VIN only as its connection point. Common ground and the exact carrier regulator circuit still require physical confirmation. The sensor VIN should have one supply source; converter output ground, module ground and ESP32 signal ground must share a reference.

With the reported 3.33 V supply, a 15-second USB probe received 15 canonical hardware snapshots. Three had algorithm quality `VALID`; the other twelve were `LOW_SIGNAL`. Paired sampling averaged 24.86 Hz with zero lost samples during the probe. **This supersedes the earlier observation of no algorithm-accepted results, but does not establish stable or accurate SpO2.** Individual accepted windows remain experimental algorithm output, not a validated physiological measurement. No calibration was invented, thresholds loosened, or values forced into a normal range. Private evidence: `.work/pulse-111-buck-333.json` and `.work/pulse-111-buck-333.log`.

A steady VIN measurement alone cannot verify the internal LED rail or exclude switching ripple. Identification of the actual carrier and further physical/reference checks remain necessary before declaring the pulse issue resolved. Firmware and APK were unchanged during this supply comparison.

The user next requested a 5 V check. A 20-second acquisition attempt was recorded as **reported-5v**, pending confirmation of the actual measured voltage and connection. It returned `UNAVAILABLE`/`SENSOR_TIMEOUT` with no fresh optical samples. This is not a successful 5 V comparison and does not establish supply voltage as the cause or prove hardware damage. When the pulse library no longer detects the sensor, diagnostic register fields fall back to zero; those zero fields must not be interpreted as verified register reads. Private evidence: `.work/pulse-111-reported-5v.json` and `.work/pulse-111-reported-5v.log`. No firmware or supply setting was changed by the laptop.
