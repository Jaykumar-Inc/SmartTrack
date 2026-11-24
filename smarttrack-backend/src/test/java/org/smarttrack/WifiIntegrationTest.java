package org.smarttrack;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.smarttrack.repository.ReadingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:wifi;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "smarttrack.bootstrap-user=wifi-owner",
      "smarttrack.bootstrap-password=wifi-fixture-password",
      "smarttrack.mock-enabled=false"
    })
@AutoConfigureMockMvc
class WifiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ReadingRepository readings;
  @Autowired ObjectMapper json;
  static final String PACKET =
      """
      {"deviceId":"wifi-watch","bootId":"wifi-boot","sequenceNo":1,"simulated":false,
      "health":{"bpm":null,"spo2":null,"fingerPresent":false,"quality":"NO_FINGER","valid":false},
      "motion":{"ax":null,"ay":null,"az":null,"gx":null,"gy":null,"gz":null,"tilt":null,"possibleFall":false,"activity":"UNAVAILABLE","quality":"CALIBRATING","fallState":"NORMAL"},
      "temperature":{"celsius":null,"quality":"UNAVAILABLE","source":"LSM6DS3_INTERNAL","unit":"C","sampleAgeMs":null}}
      """;

  String register(String id) throws Exception {
    String text =
        mvc.perform(
                post("/api/devices")
                    .with(httpBasic("wifi-owner", "wifi-fixture-password"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"deviceId\":\""
                            + id
                            + "\",\"name\":\"Wi-Fi watch\",\"simulated\":false}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(text).path("apiKey").asText();
  }

  @Test
  void wifiUsesDeviceKeyValidationSharedHistoryAndClock() throws Exception {
    String id = "wifi-auth", key = register(id), packet = PACKET.replace("wifi-watch", id);
    mvc.perform(post("/api/ingest/" + id).contentType(MediaType.APPLICATION_JSON).content(packet))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transport").value("WIFI"));
    long count = readings.count();
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet))
        .andExpect(status().isOk());
    assertEquals(count, readings.count());
    mvc.perform(get("/api/device-config/" + id).header("X-Device-Key", key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverEpoch").isNumber())
        .andExpect(jsonPath("$.sensitivity").value(1.0));
    mvc.perform(
            get("/api/devices/" + id + "/history")
                .with(httpBasic("wifi-owner", "wifi-fixture-password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].transport").value("WIFI"));
    mvc.perform(
            post("/api/bluetooth/ingest/" + id)
                .with(httpBasic("wifi-owner", "wifi-fixture-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet))
        .andExpect(status().isNotFound());
  }

  @Test
  void concurrentWifiRetriesStoreOneReading() throws Exception {
    String id = "wifi-concurrent", key = register(id), packet = PACKET.replace("wifi-watch", id);
    long before = readings.count();
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<Integer> ingest =
          () -> {
            start.await();
            return mvc.perform(
                    post("/api/ingest/" + id)
                        .header("X-Device-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packet))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var a = pool.submit(ingest);
      var b = pool.submit(ingest);
      start.countDown();
      assertEquals(200, a.get(15, TimeUnit.SECONDS));
      assertEquals(200, b.get(15, TimeUnit.SECONDS));
      assertEquals(before + 1, readings.count());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void oldCapturesStayHistorical() throws Exception {
    String id = "wifi-history",
        key = register(id),
        packet =
            PACKET
                .replace("wifi-watch", id)
                .replace(
                    "\"sequenceNo\":1", "\"sequenceNo\":1,\"capturedAt\":\"2024-01-01T00:00:00Z\"");
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(true));
    mvc.perform(get("/api/devices").with(httpBasic("wifi-owner", "wifi-fixture-password")))
        .andExpect(
            jsonPath("$[?(@.deviceId == 'wifi-history')].online")
                .value(org.hamcrest.Matchers.contains(false)));
  }

  @Test
  void temperatureWorksDuringCalibrationAndRejectsInvalidSamples() throws Exception {
    String id = "wifi-temperature", key = register(id);
    var packet =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(PACKET.replace("wifi-watch", id));
    var t = (com.fasterxml.jackson.databind.node.ObjectNode) packet.get("temperature");
    t.put("celsius", 29.25);
    t.put("quality", "GOOD");
    t.put("sampleAgeMs", 100);
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.temperature.celsius").value(29.25))
        .andExpect(jsonPath("$.motion.quality").value("CALIBRATING"))
        .andExpect(jsonPath("$.gps").doesNotExist());
    mvc.perform(
            get("/api/devices/" + id + "/temperature")
                .with(httpBasic("wifi-owner", "wifi-fixture-password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].temperature.source").value("LSM6DS3_INTERNAL"));
    packet.put("sequenceNo", 2);
    t.put("sampleAgeMs", 3000);
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet.toString()))
        .andExpect(status().isBadRequest());
    t.put("quality", "UNAVAILABLE");
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet.toString()))
        .andExpect(status().isBadRequest());
    t.put("quality", "GOOD");
    t.put("sampleAgeMs", 0);
    t.put("celsius", 86);
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet.toString()))
        .andExpect(status().isBadRequest());
    t.putNull("celsius");
    t.put("quality", "UNAVAILABLE");
    t.putNull("sampleAgeMs");
    mvc.perform(
            post("/api/ingest/" + id)
                .header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(packet.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.temperature.celsius").isEmpty());
  }
}
