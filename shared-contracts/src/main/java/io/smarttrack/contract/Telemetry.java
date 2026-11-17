package io.smarttrack.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Canonical v1 wire model. All physical values are nullable; zero is a valid value. */
public record Telemetry(
    int protocolVersion,
    String deviceId,
    String sessionId,
    long sequence,
    Instant timestamp,
    TimestampQuality timestampQuality,
    long uptimeMs,
    Source source,
    Health health,
    Motion motion,
    Gps gps,
    DeviceStatus status,
    List<AlertSignal> alerts) {
  @Override
  public String toString() {
    return "Telemetry[v="
        + protocolVersion
        + ",sequence="
        + sequence
        + ",source="
        + source
        + ",measurements=redacted]";
  }

  public enum TimestampQuality {
    NTP,
    GPS,
    SYSTEM,
    PHONE,
    UNSYNCHRONIZED
  }

  public enum Source {
    HARDWARE,
    SIMULATOR
  }

  public enum Quality {
    VALID,
    LOW_SIGNAL,
    NO_CONTACT,
    UNAVAILABLE
  }

  public enum Activity {
    STILL,
    MOVING,
    VIGOROUS,
    UNKNOWN
  }

  public enum FallState {
    IDLE,
    FREEFALL,
    IMPACT,
    MONITORING,
    FALL,
    COOLDOWN
  }

  public enum AlertKind {
    IMPACT,
    EXPERIMENTAL_FALL,
    SENSOR_UNAVAILABLE
  }

  public enum Severity {
    INFO,
    WARNING,
    CRITICAL
  }

  public record Health(
      Double heartRateBpm, Double spo2Percent, boolean fingerDetected, Quality quality) {}

  public record Motion(
      Double axG,
      Double ayG,
      Double azG,
      Double gxDps,
      Double gyDps,
      Double gzDps,
      Double temperatureC,
      Double magnitudeG,
      Double pitchDeg,
      Double rollDeg,
      Activity activity,
      FallState fallState) {}

  public record Gps(
      boolean communicating,
      boolean fix,
      Integer satellites,
      Double latitude,
      Double longitude,
      Double altitudeM,
      Double speedKmh,
      Instant lastFixAt,
      long characters,
      long checksumFailures) {}

  public record DeviceStatus(
      String firmwareVersion,
      Integer rssiDbm,
      Long freeHeapBytes,
      boolean wifiConnected,
      boolean bleConnected,
      String clockSource) {}

  public record AlertSignal(AlertKind kind, Severity severity) {}

  public void validate() {
    require(protocolVersion == 1, "Unsupported protocolVersion");
    require(deviceId != null && deviceId.matches("[A-Za-z0-9_-]{3,64}"), "Invalid deviceId");
    try {
      UUID.fromString(sessionId);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid sessionId");
    }
    require(sequence >= 0 && uptimeMs >= 0, "Negative sequence or uptimeMs");
    require(source != null && timestampQuality != null, "Missing provenance");
    require(
        (timestampQuality == TimestampQuality.UNSYNCHRONIZED) == (timestamp == null),
        "Timestamp and quality disagree");
    require(
        health != null && motion != null && gps != null && status != null && alerts != null,
        "Missing telemetry section");
    require(
        health.quality != null && motion.activity != null && motion.fallState != null,
        "Missing measurement state");
    range(health.heartRateBpm, 25, 240, "heartRateBpm");
    range(health.spo2Percent, 50, 100, "spo2Percent");
    require(
        health.quality == Quality.VALID
            || (health.heartRateBpm == null && health.spo2Percent == null),
        "Invalid health must have null values");
    require(
        health.quality != Quality.VALID
            || (health.fingerDetected && health.heartRateBpm != null && health.spo2Percent != null),
        "Valid health needs contact and estimates");
    for (Double v : new Double[] {motion.axG, motion.ayG, motion.azG})
      range(v, -16, 16, "acceleration");
    for (Double v : new Double[] {motion.gxDps, motion.gyDps, motion.gzDps})
      range(v, -2000, 2000, "angular velocity");
    range(motion.temperatureC, -40, 100, "temperatureC");
    range(motion.magnitudeG, 0, 28, "magnitudeG");
    range(motion.pitchDeg, -180, 180, "pitchDeg");
    range(motion.rollDeg, -180, 180, "rollDeg");
    range(gps.latitude, -90, 90, "latitude");
    range(gps.longitude, -180, 180, "longitude");
    range(gps.altitudeM, -1000, 20000, "altitudeM");
    range(gps.speedKmh, 0, 1500, "speedKmh");
    require(
        gps.satellites == null || (gps.satellites >= 0 && gps.satellites <= 99),
        "Invalid satellites");
    require(gps.characters >= 0 && gps.checksumFailures >= 0, "Invalid GPS counters");
    require(
        !gps.fix || (gps.communicating && gps.latitude != null && gps.longitude != null),
        "Fix needs coordinates and communication");
    require(
        gps.fix
            || (gps.latitude == null
                && gps.longitude == null
                && gps.altitudeM == null
                && gps.speedKmh == null),
        "No fix must have null location");
    require(
        status.firmwareVersion != null
            && status.firmwareVersion.length() <= 40
            && status.clockSource != null
            && status.clockSource.length() <= 24,
        "Invalid status");
    require(
        status.rssiDbm == null || (status.rssiDbm >= -127 && status.rssiDbm <= 0), "Invalid RSSI");
    require(status.freeHeapBytes == null || status.freeHeapBytes >= 0, "Invalid heap");
    require(
        alerts.size() <= 8
            && alerts.stream().allMatch(a -> a != null && a.kind != null && a.severity != null),
        "Invalid alerts");
    require(
        alerts.stream().map(AlertSignal::kind).distinct().count() == alerts.size(),
        "Duplicate alert kinds");
  }

  private static void range(Double v, double min, double max, String name) {
    require(v == null || (Double.isFinite(v) && v >= min && v <= max), "Invalid " + name);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
