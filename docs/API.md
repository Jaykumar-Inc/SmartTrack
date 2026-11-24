# Shared Wi-Fi API

Base: http://192.168.137.1:8080/api, or current address from SHOW_LOGIN.cmd.

| Route | Purpose |
|---|---|
| GET /auth/me | User login check |
| GET /devices | Owned device last-seen and online status |
| POST /devices | Register device, return its key once |
| GET /devices/{id}/latest | Latest reading, transport and stale flag |
| GET /devices/{id}/history?limit=120 | Shared history, limit 1-1000 |
| GET /devices/{id}/health, /motion, /temperature | Sensor histories |
| GET /devices/{id}/alerts | Alerts |
| POST /devices/{id}/alerts/{alertId}/ack | Acknowledge alert |
| GET/PUT /devices/{id}/settings | Motion settings |
| PUT /devices/{id}/thresholds | Optical alert thresholds |
| POST /devices/{id}/commands/cancel or calibrate | Versioned watch command |
| POST /ingest/{id} | Device-key authenticated telemetry |
| GET /device-config/{id} | Device-key authenticated settings, command versions, serverEpoch |
| /ws | Owner-filtered desktop update notifications |

User routes require HTTP Basic authentication and device ownership. Device routes require X-Device-Key, distinct from the app password.

```json
"temperature": {
  "celsius": 28.5,
  "quality": "GOOD",
  "source": "LSM6DS3_INTERNAL",
  "unit": "C",
  "sampleAgeMs": 100
}
```

The above is a contract example, not a captured measurement. GOOD requires a value within -40..85 C and age below3000ms. STALE/UNAVAILABLE require null celsius. Temperature is accepted while motion is CALIBRATING. Existing records without this new channel return null, without fabricated backfill. New temperature data lives in temperature_readings. Duplicate boot/sequence retries return the original reading. Old capture times do not mark a watch live. Fahrenheit is a client display conversion; stored values and CSV use Celsius.
