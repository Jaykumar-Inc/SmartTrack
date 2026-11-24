package org.smarttrack.entity;

import jakarta.persistence.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "device_settings")
public class DeviceSettings {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long deviceId;

  public Long getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(Long value) {
    deviceId = value;
  }

  private double sensitivity = 1;

  public double getSensitivity() {
    return sensitivity;
  }

  public void setSensitivity(double value) {
    sensitivity = value;
  }

  @Column(name = "impact_g")
  private double impactG = 2.5;

  public double getImpactG() {
    return impactG;
  }

  public void setImpactG(double value) {
    impactG = value;
  }

  @Column(name = "low_g")
  private double lowG = 0.6;

  public double getLowG() {
    return lowG;
  }

  public void setLowG(double value) {
    lowG = value;
  }

  private long inactivityMs = 2000;

  public long getInactivityMs() {
    return inactivityMs;
  }

  public void setInactivityMs(long value) {
    inactivityMs = value;
  }

  private long cooldownMs = 20000;

  public long getCooldownMs() {
    return cooldownMs;
  }

  public void setCooldownMs(long value) {
    cooldownMs = value;
  }

  private long cancelVersion = 0;

  public long getCancelVersion() {
    return cancelVersion;
  }

  public void setCancelVersion(long value) {
    cancelVersion = value;
  }

  private long calibrateVersion = 0;

  public long getCalibrateVersion() {
    return calibrateVersion;
  }

  public void setCalibrateVersion(long value) {
    calibrateVersion = value;
  }
}
