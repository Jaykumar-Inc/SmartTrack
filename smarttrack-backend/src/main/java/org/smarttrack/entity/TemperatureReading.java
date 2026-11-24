package org.smarttrack.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "temperature_readings")
public class TemperatureReading {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private Long readingId;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  public Long getReadingId() {
    return readingId;
  }

  public void setReadingId(Long value) {
    readingId = value;
  }

  private Double celsius;

  public Double getCelsius() {
    return celsius;
  }

  public void setCelsius(Double value) {
    celsius = value;
  }

  private String quality;

  public String getQuality() {
    return quality;
  }

  public void setQuality(String value) {
    quality = value;
  }

  private String source;

  public String getSource() {
    return source;
  }

  public void setSource(String value) {
    source = value;
  }

  private String unit;

  public String getUnit() {
    return unit;
  }

  public void setUnit(String value) {
    unit = value;
  }

  private Long sampleAgeMs;

  public Long getSampleAgeMs() {
    return sampleAgeMs;
  }

  public void setSampleAgeMs(Long value) {
    sampleAgeMs = value;
  }
}
