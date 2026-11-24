package org.smarttrack.dto;

import jakarta.validation.constraints.*;

public final class Requests {
  public record MotionSettings(
      @DecimalMin("0.5") @DecimalMax("2") double sensitivity,
      @DecimalMin("2") @DecimalMax("6") double impactG,
      @DecimalMin("0.2") @DecimalMax("0.8") double lowG,
      @Min(1000) @Max(10000) long inactivityMs,
      @Min(10000) @Max(120000) long cooldownMs) {}

  private Requests() {}

  public record Registration(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,80}") String deviceId,
      @NotBlank @Size(max = 80) String name,
      boolean simulated) {}

  public record Thresholds(
      @Min(20) @Max(200) double minBpm,
      @Min(30) @Max(250) double maxBpm,
      @Min(50) @Max(100) double minSpo2) {}
}
