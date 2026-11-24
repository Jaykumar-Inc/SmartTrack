package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "devices")
public class Device {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String deviceId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private Long ownerId;

  @Column(nullable = false)
  private String keyHash;

  private java.time.Instant lastSeen;
  private boolean simulated;
  private boolean connected;
  private double minBpm = 45;
  private double maxBpm = 130;
  private double minSpo2 = 92;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String value) {
    this.deviceId = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public Long getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(Long value) {
    this.ownerId = value;
  }

  public String getKeyHash() {
    return keyHash;
  }

  public void setKeyHash(String value) {
    this.keyHash = value;
  }

  public java.time.Instant getLastSeen() {
    return lastSeen;
  }

  public void setLastSeen(java.time.Instant value) {
    this.lastSeen = value;
  }

  public boolean isSimulated() {
    return simulated;
  }

  public void setSimulated(boolean value) {
    this.simulated = value;
  }

  public boolean isConnected() {
    return connected;
  }

  public void setConnected(boolean value) {
    this.connected = value;
  }

  public double getMinBpm() {
    return minBpm;
  }

  public void setMinBpm(double value) {
    this.minBpm = value;
  }

  public double getMaxBpm() {
    return maxBpm;
  }

  public void setMaxBpm(double value) {
    this.maxBpm = value;
  }

  public double getMinSpo2() {
    return minSpo2;
  }

  public void setMinSpo2(double value) {
    this.minSpo2 = value;
  }
}
