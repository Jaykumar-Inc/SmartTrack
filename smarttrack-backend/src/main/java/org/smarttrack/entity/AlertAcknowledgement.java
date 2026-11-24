package org.smarttrack.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "alert_acknowledgements")
public class AlertAcknowledgement {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long alertId;

  public Long getAlertId() {
    return alertId;
  }

  public void setAlertId(Long value) {
    alertId = value;
  }

  private Long userId;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    userId = value;
  }

  private java.time.Instant acknowledgedAt;

  public java.time.Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public void setAcknowledgedAt(java.time.Instant value) {
    acknowledgedAt = value;
  }
}
