package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@Table(name = "device_connections")
public class ConnectionEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long deviceId;

  private boolean connected;

  @Column(nullable = false)
  private java.time.Instant occurredAt;

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

  public boolean isConnected() {
    return connected;
  }

  public void setConnected(boolean value) {
    this.connected = value;
  }

  public java.time.Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(java.time.Instant value) {
    this.occurredAt = value;
  }
}
