package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@Table(name = "sensor_readings")
public class Reading {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long deviceId;

  @Column(nullable = false)
  private String bootId;

  @Column(nullable = false)
  private long sequenceNo;

  @Column(nullable = false)
  private java.time.Instant receivedAt;

  private java.time.Instant capturedAt;
  private boolean simulated;

  @Column(nullable = false, length = 24)
  private String transport = "LEGACY";

  public String getTransport() {
    return transport;
  }

  public void setTransport(String value) {
    transport = value;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(Long value) {
    this.deviceId = value;
  }

  public String getBootId() {
    return bootId;
  }

  public void setBootId(String value) {
    this.bootId = value;
  }

  public long getSequenceNo() {
    return sequenceNo;
  }

  public void setSequenceNo(long value) {
    this.sequenceNo = value;
  }

  public java.time.Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(java.time.Instant value) {
    this.receivedAt = value;
  }

  public java.time.Instant getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(java.time.Instant value) {
    this.capturedAt = value;
  }

  public boolean isSimulated() {
    return simulated;
  }

  public void setSimulated(boolean value) {
    this.simulated = value;
  }

  private Long uptimeMs;

  public Long getUptimeMs() {
    return uptimeMs;
  }

  public void setUptimeMs(Long value) {
    uptimeMs = value;
  }
}
