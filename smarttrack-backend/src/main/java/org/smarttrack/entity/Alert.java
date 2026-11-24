package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@Table(name = "alerts")
public class Alert {
  private String eventKey;

  public String getEventKey() {
    return eventKey;
  }

  public void setEventKey(String value) {
    eventKey = value;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long deviceId;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private String severity;

  @Column(nullable = false)
  private String message;

  @Column(nullable = false)
  private java.time.Instant createdAt;

  private java.time.Instant acknowledgedAt;
  private Long acknowledgedBy;

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

  public String getType() {
    return type;
  }

  public void setType(String value) {
    this.type = value;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String value) {
    this.severity = value;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String value) {
    this.message = value;
  }

  public java.time.Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(java.time.Instant value) {
    this.createdAt = value;
  }

  public java.time.Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public void setAcknowledgedAt(java.time.Instant value) {
    this.acknowledgedAt = value;
  }

  public Long getAcknowledgedBy() {
    return acknowledgedBy;
  }

  public void setAcknowledgedBy(Long value) {
    this.acknowledgedBy = value;
  }
}
