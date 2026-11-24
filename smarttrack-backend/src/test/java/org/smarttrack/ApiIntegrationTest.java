package org.smarttrack;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.smarttrack.repository.ReadingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
      "spring.datasource.username=${TEST_DB_USER:sa}",
      "spring.datasource.password=${TEST_DB_PASSWORD:}",
      "smarttrack.bootstrap-user=tester",
      "smarttrack.bootstrap-password=test-password-1234",
      "smarttrack.mock-enabled=false"
    })
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ReadingRepository readings;
  @Autowired org.smarttrack.repository.AppUserRepository users;
  @Autowired org.smarttrack.repository.DeviceRepository devices;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
  @Autowired org.smarttrack.service.TrackingService service;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  private static final String PACKET =
      """
      {"bootId":"test-boot","sequenceNo":1,"simulated":false,
      "health":{"bpm":75,"spo2":98,"fingerPresent":true,"quality":"GOOD"},
      "motion":{"ax":0,"ay":0,"az":9.81,"gx":0,"gy":0,"gz":0,"tilt":0,"possibleFall":true,"activity":"POSSIBLE_FALL"},
      "temperature":{"celsius":null,"quality":"UNAVAILABLE","source":"LSM6DS3_INTERNAL","unit":"C","sampleAgeMs":null}}
      """;

  @Test
  void completePersistenceAndFailureFlow() throws Exception {
    mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/devices").with(httpBasic("tester", "wrong")))
        .andExpect(status().isUnauthorized());
    var result =
        mvc.perform(
                post("/api/devices")
                    .with(httpBasic("tester", "test-password-1234"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"deviceId\":\"test-esp\",\"name\":\"Test ESP32\",\"simulated\":false}"))
            .andExpect(status().isCreated())
            .andReturn();
    var key = json.readTree(result.getResponse().getContentAsString()).get("apiKey").asText();
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PACKET))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PACKET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.health.bpm").value(75));
    long count = readings.count();
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PACKET))
        .andExpect(status().isOk());
    assertEquals(count, readings.count());
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    PACKET
                        .replace("\"sequenceNo\":1", "\"sequenceNo\":2")
                        .replace("\"bpm\":75", "\"bpm\":999")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/devices/test-esp/latest").with(httpBasic("tester", "test-password-1234")))
        .andExpect(jsonPath("$.motion.possibleFall").value(true))
        .andExpect(jsonPath("$.temperature.celsius").isEmpty());
    var alerts =
        mvc.perform(
                get("/api/devices/test-esp/alerts").with(httpBasic("tester", "test-password-1234")))
            .andExpect(status().isOk())
            .andReturn();
    long aid = json.readTree(alerts.getResponse().getContentAsString()).get(0).get("id").asLong();
    mvc.perform(
            post("/api/devices/test-esp/alerts/" + aid + "/ack")
                .with(httpBasic("tester", "test-password-1234")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());
    mvc.perform(get("/api/devices/unknown/latest").with(httpBasic("tester", "test-password-1234")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/devices").with(httpBasic("tester", "test-password-1234")))
        .andExpect(jsonPath("$[0].keyHash").doesNotExist())
        .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    var other = new org.smarttrack.entity.AppUser();
    other.setUsername("another-user");
    other.setPasswordHash(passwords.encode("another-test-password"));
    users.save(other);
    mvc.perform(
            get("/api/devices/test-esp/latest")
                .with(httpBasic("another-user", "another-test-password")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    PACKET
                        .replace("\"sequenceNo\":1", "\"sequenceNo\":3")
                        .replace("\"fingerPresent\":true", "\"fingerPresent\":false")))
        .andExpect(status().isBadRequest());
    assertEquals(count, readings.count());
    assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM health_readings", Long.class));
    assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM motion_readings", Long.class));
    assertEquals(
        count, jdbc.queryForObject("SELECT COUNT(*) FROM temperature_readings", Long.class));
    String old =
        PACKET.replace(
            "\"sequenceNo\":1", "\"sequenceNo\":4,\"capturedAt\":\"2020-01-01T00:00:00Z\"");
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(old))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(true));
    var d = devices.findByDeviceId("test-esp").orElseThrow();
    d.setLastSeen(java.time.Instant.now().minusSeconds(30));
    devices.save(d);
    service.checkConnections();
    mvc.perform(get("/api/devices").with(httpBasic("tester", "test-password-1234")))
        .andExpect(jsonPath("$[0].online").value(false));
    assertTrue(
        service.alerts("test-esp", "tester").stream()
            .anyMatch(a -> a.getType().equals("DISCONNECTED")));
    mvc.perform(
            put("/api/devices/test-esp/settings")
                .with(httpBasic("tester", "test-password-1234"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sensitivity\":1.2,\"impactG\":3.0,\"lowG\":0.5,\"inactivityMs\":2500,\"cooldownMs\":30000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sensitivity").value(1.2));
    mvc.perform(get("/api/device-config/test-esp").header("X-Device-Key", key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.impactG").value(3.0));
    mvc.perform(get("/api/device-config/test-esp").header("X-Device-Key", "wrong"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/devices/test-esp/commands/cancel")
                .with(httpBasic("tester", "test-password-1234")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelVersion").value(1));
    mvc.perform(
            post("/api/devices/test-esp/commands/calibrate")
                .with(httpBasic("tester", "test-password-1234")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calibrateVersion").value(1));
    var extended = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(PACKET);
    extended.put("deviceId", "test-esp");
    extended.put("sequenceNo", 6);
    extended.put("uptimeMs", 120000);
    var motion = (com.fasterxml.jackson.databind.node.ObjectNode) extended.get("motion");
    motion.put("az", 1.0);
    motion.put("magnitude", 1.0);
    motion.put("pitch", 1.2);
    motion.put("roll", -0.8);
    motion.put("temperature", 28);
    motion.put("quality", "GOOD");
    motion.put("calibration", "CALIBRATED");
    motion.put("fallState", "FALL_ALERT");
    motion.put("eventId", 777);
    motion.put("impactCount", 1);
    motion.put("accelerationUnit", "g");
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(extended.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.motion.pitch").value(1.2))
        .andExpect(jsonPath("$.motion.accelerationUnit").value("g"))
        .andExpect(jsonPath("$.uptimeMs").value(120000));
    var algorithmic =
        service.alerts("test-esp", "tester").stream()
            .filter(a -> a.getType().equals("ALGORITHMIC_FALL"))
            .findFirst()
            .orElseThrow();
    service.acknowledge("test-esp", "tester", algorithmic.getId());
    extended.put("sequenceNo", 7);
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(extended.toString()))
        .andExpect(status().isOk());
    assertEquals(
        1,
        service.alerts("test-esp", "tester").stream()
            .filter(a -> a.getType().equals("ALGORITHMIC_FALL"))
            .count());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM alert_acknowledgements", Long.class));
    extended.put("sequenceNo", 8);
    motion.put("accelerationUnit", "m/s2");
    mvc.perform(
            post("/api/ingest/test-esp")
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(extended.toString()))
        .andExpect(status().isBadRequest());
  }
}
