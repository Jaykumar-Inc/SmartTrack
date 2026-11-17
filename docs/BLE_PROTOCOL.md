# SmartTrack GATT v1

Service UUID: `7fd90000-9c24-4f64-a65e-8a08cc91f001`. Characteristic UUIDs replace the final service byte `00` with the suffix below. Advertisement includes the service UUID and a SmartTrack device name. Android scans for the UUID, not arbitrary nearby devices.

| Suffix | Characteristic | Operations |
|---|---|---|
| 01 | Device identity | Public read: deviceId, firmwareVersion |
| 02 | Atomic telemetry / health | Authenticated encrypted read/notify; notifications carry framed complete canonical snapshots |
| 03 | Motion projection | Authenticated encrypted read of latest motion JSON |
| 04 | GPS projection | Authenticated encrypted read of latest GPS JSON |
| 05 | Alerts projection | Authenticated encrypted read of latest alert array |
| 06 | Delivery configuration | Authenticated encrypted read/write: `{"telemetryIntervalMs":1000}`; accepted range 1000–60000 |
| 07 | Clock synchronization | Authenticated encrypted write: `{"epochSeconds":1794787200}`; NTP/GPS retain priority |

One complete v1 snapshot is streamed through suffix 02 so the client cannot combine mismatched health, motion and GPS sequences. The other projections are supplemental read views; their notify property is reserved and Android does not subscribe to them. Live changes are delivered through the atomic snapshot. Reading suffix 02 returns its most recent fragment, not the entire snapshot; clients must use notifications for full records. Identity is intentionally public; measurements are not.

Packet layout (little endian):

| Byte | Meaning |
|---|---|
| 0 | Framing version = 1 |
| 1 | Type = 1, canonical UTF-8 snapshot |
| 2–3 | Frame ID (low 16 bits of sequence) |
| 4–5 | Zero-based fragment index |
| 6–7 | Fragment count |
| 8… | UTF-8 bytes |

Fragment payload maximum: negotiated ATT MTU − 3 − 8. Default MTU 23 works; firmware caps MTU usage at 185. JSON is encoded once and fragmented as bytes, so multibyte characters may cross a fragment boundary. Decode UTF-8 only after reassembly. The canonical session UUID and full sequence remain in the JSON, independent of the rolling frame ID.

Android reassembly permits at most two concurrent messages, 256 fragments/message, 16 KiB/message and a 10 s lifetime. Duplicate identical fragments are harmless. Conflicting fragments, invalid headers or changed fragment counts reject the message. Partial messages do not become measurements. GATT notifications are not guaranteed delivery; the next snapshot recovers the current signal. Wi-Fi and Android sync deduplicate on the canonical tuple.

ESP32 requests LE Secure Connections, MITM authentication and bonding with display-only I/O. Enter the six-digit OLED code on Android's pairing prompt. Notifications are gated on successful authenticated encryption; configuration and clock writes require encrypted authenticated access. Identity contains no credentials. Provision Wi-Fi credentials and device tokens only over serial; they are not accepted over BLE. Bond storage is maintained by NimBLE. A physical pairing/unpair policy and bonded-phone lifecycle need bench validation before a public product release.

Android uses service discovery, MTU negotiation, CCCD subscription and acknowledged characteristic writes. API 31+ needs Bluetooth scan/connect permission; older supported Android versions need location permission for discovery. Permission denial leaves the app usable in backend mode. Reconnection uses capped exponential delays and explicit disconnect cancels it. A Windows BLE console monitor/backend bridge is implemented in `scripts/ble-monitor.py`; fresh passkey pairing, authenticated encryption, controls and actual notifications were physically tested on the laptop. Android waits for bonding before negotiating MTU and subscribing; successful descriptor acknowledgement precedes the ready state. The actual iQOO 12 (API 36) passed discovery, live authenticated notifications, account sharing and remembered reconnect. Physical minimum-MTU radio behavior, fresh Android bond lifecycle and long simultaneous-radio performance remain pending. USB `paircode` exposes the OLED passkey only to the physically connected operator; it is never included in radio telemetry or test logs.

Firmware 1.1.0-dev also provides independent USB telemetry: `usbstream on` renews a 15-second lease, `usbstream off` stops it, and `clock UNIX_SECONDS` offers fallback time. Each UART snapshot is `TELEMETRY ` plus canonical JSON. Output runs in the delivery worker. The PC monitor renews every three seconds. USB and phone BLE operated concurrently in physical tests; BLE itself still has one central slot. See [current evidence](CONNECT_AND_SHARE_1_1_0.md).
