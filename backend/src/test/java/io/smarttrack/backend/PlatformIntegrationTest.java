package io.smarttrack.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlatformIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  HttpResponse<String> call(String method, String path, String token, Object body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
    else
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode body(HttpResponse<String> response) throws Exception {
    return json.readTree(response.body());
  }

  String user() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var credentials = Map.of("email", email, "password", "test-passphrase-123");
    assertEquals(201, call("POST", "/v1/auth/register", null, credentials).statusCode());
    return body(call("POST", "/v1/auth/login", null, credentials)).get("token").asText();
  }

  ObjectNode telemetry(String device) throws Exception {
    var n =
        (ObjectNode) json.readTree(Path.of("../shared-contracts/example-simulator.json").toFile());
    n.put("deviceId", device);
    n.put("timestamp", Instant.now().toString());
    return n;
  }

  @Test
  void publicIdentityContainsOnlyProtocolMetadata() throws Exception {
    var response = call("GET", "/v1/info", null, null);
    assertEquals(200, response.statusCode());
    var value = body(response);
    assertEquals("io.smarttrack.backend", value.path("application").asText());
    assertEquals(1, value.path("telemetryProtocolVersion").asInt());
    assertEquals(3, value.size());
  }

  @Test
  void authExplainsValidationAndDuplicateAccounts() throws Exception {
    var invalid =
        call("POST", "/v1/auth/register", null, Map.of("email", "bad", "password", "short"));
    assertEquals(400, invalid.statusCode());
    assertTrue(body(invalid).path("detail").asText().contains("valid email"));
    assertTrue(body(invalid).path("detail").asText().contains("Password"));
    var credentials =
        Map.of("email", UUID.randomUUID() + "@example.test", "password", "account-test-123");
    assertEquals(201, call("POST", "/v1/auth/register", null, credentials).statusCode());
    var duplicate = call("POST", "/v1/auth/register", null, credentials);
    assertEquals(409, duplicate.statusCode());
    assertTrue(body(duplicate).path("detail").asText().contains("Sign in instead"));
    var wrong =
        call(
            "POST",
            "/v1/auth/login",
            null,
            Map.of("email", credentials.get("email"), "password", "wrong-password-123"));
    assertEquals(401, wrong.statusCode());
    assertEquals("Invalid email or password", body(wrong).path("detail").asText());
    assertEquals(200, call("POST", "/v1/auth/login", null, credentials).statusCode());
  }

  @Test
  void completePathDedupHistoryAlertsAndOwnerIsolation() throws Exception {
    String owner = user(), other = user(), device = "test-" + UUID.randomUUID();
    var registered =
        call("POST", "/v1/devices", owner, Map.of("id", device, "name", "Integration device"));
    assertEquals(201, registered.statusCode());
    String secret = body(registered).get("deviceToken").asText();
    ObjectNode t = telemetry(device);
    t.withArray("alerts").addObject().put("kind", "IMPACT").put("severity", "WARNING");
    assertEquals(401, call("POST", "/v1/telemetry", null, t).statusCode());
    assertEquals(404, call("POST", "/v1/telemetry", other, t).statusCode());
    var accepted = body(call("POST", "/v1/telemetry", secret, t));
    assertFalse(accepted.get("duplicate").asBoolean());
    assertTrue(body(call("POST", "/v1/telemetry", secret, t)).get("duplicate").asBoolean());
    t.put("uptimeMs", 3000);
    assertEquals(409, call("POST", "/v1/telemetry", secret, t).statusCode());
    assertEquals(
        "SIMULATED",
        body(call("GET", "/v1/devices/" + device + "/latest", owner, null))
            .get("freshness")
            .asText());
    assertEquals(404, call("GET", "/v1/devices/" + device + "/latest", other, null).statusCode());
    assertEquals(403, call("GET", "/v1/devices", secret, null).statusCode());
    String query =
        "?from=" + Instant.now().minusSeconds(60) + "&to=" + Instant.now().plusSeconds(60);
    assertEquals(
        1, body(call("GET", "/v1/devices/" + device + "/history" + query, owner, null)).size());
    var alerts = body(call("GET", "/v1/devices/" + device + "/alerts", owner, null));
    assertEquals(1, alerts.size());
    String alert = alerts.get(0).get("id").asText();
    assertEquals(
        404, call("POST", "/v1/alerts/" + alert + "/acknowledgement", other, null).statusCode());
    assertEquals(
        200, call("POST", "/v1/alerts/" + alert + "/acknowledgement", owner, null).statusCode());
    assertEquals(
        200, call("POST", "/v1/alerts/" + alert + "/acknowledgement", owner, null).statusCode());
    assertFalse(
        body(call("GET", "/v1/devices/" + device + "/alerts", owner, null))
            .get(0)
            .get("acknowledged_at")
            .isNull());
    assertEquals(
        200,
        call(
                "PUT",
                "/v1/devices/" + device + "/configuration",
                owner,
                Map.of("revision", 0, "telemetryIntervalMs", 2000, "timezone", "Asia/Kolkata"))
            .statusCode());
    assertEquals(
        409,
        call(
                "PUT",
                "/v1/devices/" + device + "/configuration",
                owner,
                Map.of("revision", 0, "telemetryIntervalMs", 2000, "timezone", "Asia/Kolkata"))
            .statusCode());
    assertEquals(
        1, body(call("GET", "/v1/devices/" + device + "/synchronization", owner, null)).size());
  }

  @Test
  void invalidDataAndDeviceScopeAreRejected() throws Exception {
    String owner = user(), a = "scope-" + UUID.randomUUID(), b = "scope-" + UUID.randomUUID();
    String token =
        body(call("POST", "/v1/devices", owner, Map.of("id", a, "name", "A")))
            .get("deviceToken")
            .asText();
    call("POST", "/v1/devices", owner, Map.of("id", b, "name", "B"));
    assertEquals(403, call("POST", "/v1/telemetry", token, telemetry(b)).statusCode());
    var t = telemetry(a);
    ((ObjectNode) t.get("health")).put("heartRateBpm", 0);
    assertEquals(400, call("POST", "/v1/telemetry", token, t).statusCode());
    t = telemetry(a);
    t.put("timestamp", Instant.now().plusSeconds(600).toString());
    assertEquals(400, call("POST", "/v1/telemetry", token, t).statusCode());
    t = telemetry(a);
    t.remove("sequence");
    assertEquals(400, call("POST", "/v1/telemetry", token, t).statusCode());
    t = telemetry(a);
    t.put("unexpectedField", 1);
    assertEquals(400, call("POST", "/v1/telemetry", token, t).statusCode());
    call("POST", "/v1/devices/" + a + "/rotate-token", owner, null);
    assertEquals(401, call("GET", "/v1/devices/" + a + "/configuration", token, null).statusCode());
  }

  @Test
  void websocketDeliversCommittedReading() throws Exception {
    String owner = user(), device = "stream-" + UUID.randomUUID();
    String secret =
        body(call("POST", "/v1/devices", owner, Map.of("id", device, "name", "Stream")))
            .get("deviceToken")
            .asText();
    var received = new CompletableFuture<String>();
    var ready = new CompletableFuture<Void>();
    WebSocket socket =
        client
            .newWebSocketBuilder()
            .header("Authorization", "Bearer " + owner)
            .buildAsync(
                URI.create("ws://127.0.0.1:" + port + "/v1/stream?deviceId=" + device),
                new WebSocket.Listener() {
                  StringBuilder parts = new StringBuilder();

                  public void onOpen(WebSocket s) {
                    s.request(1);
                  }

                  public CompletionStage<?> onText(WebSocket s, CharSequence data, boolean last) {
                    parts.append(data);
                    if (last) {
                      try {
                        JsonNode frame = json.readTree(parts.toString());
                        if (frame.path("type").asText().equals("ready")) ready.complete(null);
                        else if (frame.has("telemetry")) received.complete(parts.toString());
                      } catch (Exception e) {
                        received.completeExceptionally(e);
                      }
                      parts.setLength(0);
                    }
                    s.request(1);
                    return null;
                  }
                })
            .get(10, TimeUnit.SECONDS);
    try {
      ready.get(10, TimeUnit.SECONDS);
      assertEquals(200, call("POST", "/v1/telemetry", secret, telemetry(device)).statusCode());
      assertEquals(
          device,
          json.readTree(received.get(10, TimeUnit.SECONDS))
              .get("telemetry")
              .get("deviceId")
              .asText());
    } finally {
      socket.sendClose(1000, "test complete").join();
    }
  }

  @Test
  void concurrentTransportRetriesCreateOneBatch() throws Exception {
    String owner = user(), device = "race-" + UUID.randomUUID();
    String secret =
        body(call(
                "POST", "/v1/devices", owner, Map.of("id", device, "name", "Concurrent transport")))
            .get("deviceToken")
            .asText();
    ObjectNode t = telemetry(device);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      List<java.util.concurrent.Future<HttpResponse<String>>> responses = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        responses.add(executor.submit(() -> call("POST", "/v1/telemetry", secret, t)));
      int fresh = 0;
      for (var response : responses) {
        var r = response.get(15, TimeUnit.SECONDS);
        assertEquals(200, r.statusCode());
        if (!body(r).get("duplicate").asBoolean()) fresh++;
      }
      assertEquals(1, fresh);
    }
  }
}
