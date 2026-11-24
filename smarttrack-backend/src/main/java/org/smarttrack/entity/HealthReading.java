package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@Table(name = "health_readings")
public class HealthReading {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private Long readingId;

  private Double bpm;
  private Double spo2;
  private boolean fingerPresent;

  @Column(nullable = false)
  private String quality;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getReadingId() {
    return readingId;
  }

  public void setReadingId(Long value) {
    this.readingId = value;
  }

  public Double getBpm() {
    return bpm;
  }

  public void setBpm(Double value) {
    this.bpm = value;
  }

  public Double getSpo2() {
    return spo2;
  }

  public void setSpo2(Double value) {
    this.spo2 = value;
  }

  public boolean isFingerPresent() {
    return fingerPresent;
  }

  public void setFingerPresent(boolean value) {
    this.fingerPresent = value;
  }

  public String getQuality() {
    return quality;
  }

  public void setQuality(String value) {
    this.quality = value;
  }
}
