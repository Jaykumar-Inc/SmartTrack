package org.smarttrack.entity;

import jakarta.persistence.*;

/** Encapsulation: private fields and controlled access; no annotation processor required. */
@Entity
@Table(name = "motion_readings")
public class MotionReading {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private Long readingId;

  private Double ax;
  private Double ay;
  private Double az;
  private Double gx;
  private Double gy;
  private Double gz;
  private Double tilt;
  private boolean possibleFall;

  @Column(nullable = false)
  private String activity;

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

  public Double getAx() {
    return ax;
  }

  public void setAx(Double value) {
    this.ax = value;
  }

  public Double getAy() {
    return ay;
  }

  public void setAy(Double value) {
    this.ay = value;
  }

  public Double getAz() {
    return az;
  }

  public void setAz(Double value) {
    this.az = value;
  }

  public Double getGx() {
    return gx;
  }

  public void setGx(Double value) {
    this.gx = value;
  }

  public Double getGy() {
    return gy;
  }

  public void setGy(Double value) {
    this.gy = value;
  }

  public Double getGz() {
    return gz;
  }

  public void setGz(Double value) {
    this.gz = value;
  }

  public Double getTilt() {
    return tilt;
  }

  public void setTilt(Double value) {
    this.tilt = value;
  }

  public boolean isPossibleFall() {
    return possibleFall;
  }

  public void setPossibleFall(boolean value) {
    this.possibleFall = value;
  }

  public String getActivity() {
    return activity;
  }

  public void setActivity(String value) {
    this.activity = value;
  }

  private Double pitch;

  public Double getPitch() {
    return pitch;
  }

  public void setPitch(Double value) {
    pitch = value;
  }

  private Double roll;

  public Double getRoll() {
    return roll;
  }

  public void setRoll(Double value) {
    roll = value;
  }

  private Double magnitude;

  public Double getMagnitude() {
    return magnitude;
  }

  public void setMagnitude(Double value) {
    magnitude = value;
  }

  private Double temperature;

  public Double getTemperature() {
    return temperature;
  }

  public void setTemperature(Double value) {
    temperature = value;
  }

  private String fallState = "NORMAL";

  public String getFallState() {
    return fallState;
  }

  public void setFallState(String value) {
    fallState = value;
  }

  private String quality = "UNAVAILABLE";

  public String getQuality() {
    return quality;
  }

  public void setQuality(String value) {
    quality = value;
  }

  private String calibration = "UNVERIFIED";

  public String getCalibration() {
    return calibration;
  }

  public void setCalibration(String value) {
    calibration = value;
  }

  private Long eventId;

  public Long getEventId() {
    return eventId;
  }

  public void setEventId(Long value) {
    eventId = value;
  }

  private Long impactCount;

  public Long getImpactCount() {
    return impactCount;
  }

  public void setImpactCount(Long value) {
    impactCount = value;
  }

  private boolean impact;

  public boolean isImpact() {
    return impact;
  }

  public void setImpact(boolean value) {
    impact = value;
  }

  private boolean inactive;

  public boolean isInactive() {
    return inactive;
  }

  public void setInactive(boolean value) {
    inactive = value;
  }

  private String accelerationUnit = "g";

  public String getAccelerationUnit() {
    return accelerationUnit;
  }

  public void setAccelerationUnit(String value) {
    accelerationUnit = value;
  }
}
