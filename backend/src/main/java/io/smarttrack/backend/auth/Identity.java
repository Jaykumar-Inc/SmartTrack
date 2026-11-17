package io.smarttrack.backend.auth;

public record Identity(String userId, String deviceId) {
  public boolean isDevice() {
    return deviceId != null;
  }
}
