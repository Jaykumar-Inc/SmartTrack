package io.smarttrack.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackendEndpointTest {
  @Test
  void acceptsLocalAndRemoteAddresses() {
    assertEquals("http://127.0.0.1:8087", BackendEndpoint.normalize(" http://127.0.0.1:8087/ "));
    assertEquals(
        "https://signals.example/api", BackendEndpoint.normalize("https://signals.example/api/"));
    assertTrue(BackendEndpoint.EMULATOR.endsWith(":8087"));
  }

  @Test
  void rejectsAmbiguousAndCredentialBearingAddresses() {
    for (String address :
        new String[] {
          "",
          "localhost:8087",
          "ftp://host",
          "http://u:p@host",
          "http://host?token=x",
          "http://host:99999"
        })
      assertThrows(
          IllegalArgumentException.class, () -> BackendEndpoint.normalize(address), address);
  }

  @Test
  void connectionFailuresHaveRecoveryInstructions() {
    assertTrue(
        BackendEndpoint.failure(new java.net.ConnectException(), BackendEndpoint.DESKTOP)
            .contains("Start-Backend.cmd"));
    assertTrue(
        BackendEndpoint.failure(
                new javax.net.ssl.SSLException("certificate"), BackendEndpoint.DESKTOP)
            .contains("certificate"));
    assertEquals(
        "Invalid email or password",
        BackendEndpoint.failure(
            new IllegalStateException("Invalid email or password"), BackendEndpoint.DESKTOP));
  }
}
