# Architecture decisions

## ADR 001 — One modular repository and one backend

Java 21 is the backend/desktop target. The dependency-free canonical contract targets Java 17 so Android can desugar the same records. Android compiles the shared contract source directly; it does not maintain a separate DTO copy. ESP32 C++ produces the same JSON field names. Schema/fixture validation tests detect drift. No ESP32 or client database connection exists.

## ADR 002 — Explicit provenance and incomplete clocks

Every snapshot declares HARDWARE or SIMULATOR. Simulator ingestion is disabled by default. UTC timestamps are null only when UNSYNCHRONIZED. Source, receipt time, measurement time, validity and freshness remain separate. A newly uploaded old reading is delayed, not live. A disconnected client labels retained readings cached. Simulated readings retain that label even when offline. No GPS fix is fabricated by the development simulator.

## ADR 003 — Canonical snapshot, indexed projections

The full validated snapshot is retained in `telemetry_batch`. Health, motion and GPS tables index/select key values without losing less-common diagnostics in JSON. Device identity is JPA-managed; transaction-focused ingestion uses JDBC under the same Spring transaction manager. Flyway owns schema creation; Hibernate validates it. The default database is MySQL. H2 is a test/development convenience, and verification against each is reported separately.

## ADR 004 — Retry-safe ingestion

The unique tuple `(deviceId, sessionId, sequence)` identifies a sample across Wi-Fi and Android uploads. Backend READ COMMITTED transactions lock the device row before checking/inserting a tuple and projections. This explicitly avoids MySQL REPEATABLE READ snapshots established during earlier authorization checks from hiding another transport's newly committed sample. Identical retries return the original ID. Conflicting content returns 409. Alerts are committed with their originating batch. Sync batches commit per sample; clients retry the whole batch after partial failure, relying on deduplication. High-water marks are not contiguous acknowledgements; holes must not be discarded.

## ADR 005 — Owner-scoped opaque credentials

Passwords use BCrypt cost 12. Random 256-bit user and device bearer secrets are SHA-256 hashed in storage. User tokens expire after 12 hours and support revocation; device tokens support rotation. Registry, acknowledgement and configuration writes require user credentials. Device credentials cannot act on another device. All owned-resource access is checked server-side. No secrets are committed.

## ADR 006 — Live streaming with recovery

The WebSocket upgrade uses the Authorization header and an authorized device ID; tokens never enter URLs. Events are sent after database commit. Desktop also polls latest readings every three seconds to recover missed stream updates. Historical fetches are bounded. WebSocket delivery is not an audit log; reconnecting clients recover via REST. Direct Windows BLE is deliberately absent.

## ADR 007 — Sensor sampling and transport separation

The ESP32 loop drains pulse samples, reads the IMU on a 10 ms scheduler, parses bounded UART data, and refreshes OLED once per second. Independent core-0 FreeRTOS workers handle HTTP and BLE. Wi-Fi queue: 16 packets × 2048 bytes. BLE queue: two packets. Full queues drop new packets and preserve in-flight Wi-Fi retry identity; dropped Wi-Fi packets are counted in diagnostics. Buffers are RAM-only. Power cycling loses queued data. Actual sampling jitter and coexistence heap are pending bench measurements.

## ADR 008 — Experimental measurement algorithms

MAX30102 uses SparkFun's Maxim reference algorithm over 100 samples at an effective 25 Hz, with contact, amplitude, saturation and output range checks. Invalid values are null. LSM6DS3 supplies raw acceleration for impact detection and low-pass filtered orientation/gyro values. Stationary 256-sample gyro calibration is initiated with the serial `calibrate` command and retained in RAM. These algorithms are not clinical or emergency systems.

## ADR 009 — Clock provenance

The ESP32 system clock is unsynchronized at boot. NTP has priority, then fresh GPS UTC, then a running previously synchronized system clock. A paired phone can supply UTC only when NTP/GPS are unavailable. The OLED uses fixed Asia/Kolkata (IST, UTC+05:30). No valid time is claimed after power loss without a fresh source. Desktop/mobile display zones are configurable independently. Firmware currently applies backend delivery intervals; configurable device display timezones remain pending.

## ADR 010 — Native clients and bounded local history

JavaFX uses asynchronous Java HttpClient calls, FX-thread updates, supplier-bound live fields, and a maximum 2,000-sample history (600 rendered chart samples). Charts break at invalid measurements. Settings forms survive live updates. Android uses XML/View Binding, ViewModel/LiveData, Retrofit/OkHttp and Room. Android Keystore encrypts cache payloads and tokens; index metadata remains readable inside the app sandbox. Cache: 7 days/10,000 samples, visible query: 500 across devices. Optional WorkManager uploads preserve original identity and provenance. No guaranteed background BLE monitoring is claimed.

## ADR 011 — Restrained native identity

The shared palette uses dark navy surfaces with a restrained teal signal accent. Segoe UI on Windows and Android sans are system-provided fonts, avoiding an unlicensed font bundle. Labels, units, precision and connection terminology are shared. Line icons are original vectors; no external icon/font service is required. There are no fabricated waveforms or decorative charts.

## ADR 012 — Release evidence is explicit

Source implementation, successful builds, automated tests, runtime UI checks, and physical hardware checks are different statuses. A binary build is not hardware evidence. Unsigned development artifacts are not a production release. Versioned migration history and signed release keys must be managed before distribution. See the status and limitations documents.
