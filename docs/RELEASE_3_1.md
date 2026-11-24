# SmartTrack 3.1 temperature update

This revision synchronizes the current working project into Git using the smarttrack-backend, smarttrack-desktop, smarttrack-android and esp32-firmware modules. These modules were present in the inspected working folder; the remote repository previously contained an older module layout. Prior commits remain in Git history.

The active system uses Wi-Fi and the LSM6DS3 internal temperature channel. It adds dedicated screens, C/F display, valid-window statistics, history/CSV, freshness validation, a temperature warning and a Flyway table. HTTP failures use bounded retry delays. Boot probes check the three expected I2C modules. The watch retains optical acquisition, motion calibration/fall logic, touch navigation, timeout, brightness and raise-to-wake.

Updated Android and portable Windows packages, backend JAR, source ZIP, circuit/install guides, a 14 page report and 15 slide presentation accompany the source. Existing database and private provisioning remain local. Physical verification limits are listed in TEMPERATURE_VERIFICATION.md.
