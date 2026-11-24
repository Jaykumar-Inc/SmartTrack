package org.smarttrack.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** Wire contract: acceleration in g, angular velocity deg/s, internal chip temperature in C. */
public record Telemetry(
    @NotBlank @Size(max = 80) String bootId,
    @PositiveOrZero long sequenceNo,
    Instant capturedAt,
    boolean simulated,
    @NotNull @Valid Health health,
    @NotNull @Valid Motion motion,
    @NotNull @Valid Temperature temperature,
    @Size(max = 80) String deviceId,
    @PositiveOrZero Long uptimeMs) {
  public Telemetry(
      String bootId,
      long sequenceNo,
      Instant capturedAt,
      boolean simulated,
      Health health,
      Motion motion,
      Temperature temperature) {
    this(bootId, sequenceNo, capturedAt, simulated, health, motion, temperature, null, null);
  }

  public record Health(
      @JsonAlias("heartRate") @DecimalMin("20") @DecimalMax("250") Double bpm,
      @DecimalMin("0") @DecimalMax("100") Double spo2,
      @JsonAlias("fingerDetected") boolean fingerPresent,
      @NotNull @Pattern(regexp = "GOOD|POOR|NO_FINGER|UNAVAILABLE") String quality,
      Boolean valid) {
    public Health(Double bpm, Double spo2, boolean fingerPresent, String quality) {
      this(bpm, spo2, fingerPresent, quality, null);
    }
  }

  public record Motion(
      @JsonAlias("accelerationX") @DecimalMin("-16") @DecimalMax("16") Double ax,
      @JsonAlias("accelerationY") @DecimalMin("-16") @DecimalMax("16") Double ay,
      @JsonAlias("accelerationZ") @DecimalMin("-16") @DecimalMax("16") Double az,
      @JsonAlias("gyroX") @DecimalMin("-2000") @DecimalMax("2000") Double gx,
      @JsonAlias("gyroY") @DecimalMin("-2000") @DecimalMax("2000") Double gy,
      @JsonAlias("gyroZ") @DecimalMin("-2000") @DecimalMax("2000") Double gz,
      @DecimalMin("0") @DecimalMax("180") Double tilt,
      boolean possibleFall,
      @NotNull @Pattern(regexp = "REST|STATIONARY|MOVING|POSSIBLE_FALL|UNAVAILABLE")
          String activity,
      @DecimalMin("-90") @DecimalMax("90") Double pitch,
      @DecimalMin("-180") @DecimalMax("180") Double roll,
      @JsonAlias("accelerationMagnitude") @DecimalMin("0") @DecimalMax("28") Double magnitude,
      @DecimalMin("-50") @DecimalMax("125") Double temperature,
      @Pattern(regexp = "NORMAL|POSSIBLE_FALL|VERIFYING|FALL_ALERT|RECOVERY") String fallState,
      @Pattern(regexp = "GOOD|CALIBRATING|UNAVAILABLE") String quality,
      @Size(max = 80) String calibration,
      @PositiveOrZero Long eventId,
      @PositiveOrZero Long impactCount,
      boolean impact,
      boolean inactive,
      @Pattern(regexp = "g") String accelerationUnit) {
    public Motion(
        Double ax,
        Double ay,
        Double az,
        Double gx,
        Double gy,
        Double gz,
        Double tilt,
        boolean possibleFall,
        String activity) {
      this(
          ax,
          ay,
          az,
          gx,
          gy,
          gz,
          tilt,
          possibleFall,
          activity,
          null,
          null,
          ax == null || ay == null || az == null ? null : Math.sqrt(ax * ax + ay * ay + az * az),
          null,
          possibleFall ? "POSSIBLE_FALL" : "NORMAL",
          ax == null ? "UNAVAILABLE" : "GOOD",
          "UNVERIFIED",
          null,
          null,
          false,
          false,
          "g");
    }
  }

  public record Temperature(
      @DecimalMin("-40") @DecimalMax("85") Double celsius,
      @NotNull @Pattern(regexp = "GOOD|STALE|UNAVAILABLE") String quality,
      @NotNull @Pattern(regexp = "LSM6DS3_INTERNAL") String source,
      @NotNull @Pattern(regexp = "C") String unit,
      @PositiveOrZero Long sampleAgeMs) {}
}
