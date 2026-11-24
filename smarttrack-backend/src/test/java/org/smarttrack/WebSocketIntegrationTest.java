package org.smarttrack;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.smarttrack.dto.*;
import org.smarttrack.entity.AppUser;
import org.smarttrack.repository.AppUserRepository;
import org.smarttrack.service.TrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ws;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "smarttrack.bootstrap-user=socket-owner",
      "smarttrack.bootstrap-password=socket-test-password",
      "smarttrack.mock-enabled=false"
    })
class WebSocketIntegrationTest {
  @LocalServerPort int port;
  @Autowired TrackingService service;
  @Autowired AppUserRepository users;
  @Autowired PasswordEncoder passwords;

  static class Listener implements WebSocket.Listener {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final StringBuilder buffer = new StringBuilder();

    public void onOpen(WebSocket ws) {
      ws.request(1);
    }

    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      buffer.append(data);
      if (last) {
        messages.add(buffer.toString());
        buffer.setLength(0);
      }
      ws.request(1);
      return null;
    }
  }

  private WebSocket connect(HttpClient http, String user, String password, Listener listener)
      throws Exception {
    return http.newWebSocketBuilder()
        .header(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .buildAsync(URI.create("ws://localhost:" + port + "/api/ws"), listener)
        .get(8, TimeUnit.SECONDS);
  }

  @Test
  void authenticatesAndBroadcastsCommittedReadingsOnlyToOwner() throws Exception {
    var other = new AppUser();
    other.setUsername("socket-other");
    other.setPasswordHash(passwords.encode("socket-other-password"));
    users.save(other);
    var registered =
        service.register(
            new Requests.Registration("socket-esp", "Socket ESP32", false), "socket-owner");
    try (HttpClient http = HttpClient.newHttpClient()) {
      Listener owner = new Listener(), outsider = new Listener();
      WebSocket first = connect(http, "socket-owner", "socket-test-password", owner),
          second = connect(http, "socket-other", "socket-other-password", outsider);
      assertTrue(owner.messages.poll(3, TimeUnit.SECONDS).contains("ready"));
      assertTrue(outsider.messages.poll(3, TimeUnit.SECONDS).contains("ready"));
      assertThrows(
          ExecutionException.class,
          () -> connect(http, "socket-owner", "wrong-password", new Listener()));
      service.ingest(
          "socket-esp",
          registered.get("apiKey").toString(),
          new Telemetry(
              "socket-boot",
              1,
              Instant.now(),
              false,
              new Telemetry.Health(75.0, 98.0, true, "GOOD"),
              new Telemetry.Motion(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, false, "REST"),
              new Telemetry.Temperature(null, "UNAVAILABLE", "LSM6DS3_INTERNAL", "C", null)));
      assertTrue(owner.messages.poll(3, TimeUnit.SECONDS).contains("update"));
      assertNull(outsider.messages.poll(300, TimeUnit.MILLISECONDS));
      first.abort();
      second.abort();
    }
  }
}
