package org.smarttrack.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServerAddressTest {
  @Test
  void acceptsWifiBackendAndRejectsCredentialsInUrl() {
    try (var api = new ApiClient("http://192.168.137.1:8080", "fixture", "fixture")) {
      assertEquals("http://192.168.137.1:8080", api.baseUrl());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApiClient("http://user:secret@192.168.137.1:8080", "u", "p"));
  }
}
