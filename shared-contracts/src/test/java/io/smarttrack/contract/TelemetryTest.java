package io.smarttrack.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class TelemetryTest {
  @Test
  void schemaAndCanonicalRecordHaveIdenticalFields() throws Exception {
    JsonNode schema = mapper.readTree(Path.of("telemetry-v1.schema.json").toFile());
    assertFields(Telemetry.class, schema);
    for (var component : Telemetry.class.getRecordComponents())
      if (component.getType().isRecord())
        assertFields(component.getType(), schema.path("properties").path(component.getName()));
    assertFields(Telemetry.AlertSignal.class, schema.at("/properties/alerts/items"));
  }

  private void assertFields(Class<?> record, JsonNode schema) {
    var actual =
        java.util.Arrays.stream(record.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
    java.util.Set<String> expected = new java.util.HashSet<>();
    schema.path("properties").fieldNames().forEachRemaining(expected::add);
    assertEquals(expected, actual, record.getSimpleName());
    java.util.Set<String> required = new java.util.HashSet<>();
    schema.path("required").forEach(n -> required.add(n.asText()));
    assertEquals(actual, required);
  }

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private ObjectNodeFixture fixture() throws Exception {
    return new ObjectNodeFixture(
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(Path.of("example-simulator.json").toFile()));
  }

  private record ObjectNodeFixture(com.fasterxml.jackson.databind.node.ObjectNode node) {}

  @Test
  void canonicalFixtureRoundTrips() throws Exception {
    var t = mapper.treeToValue(fixture().node, Telemetry.class);
    t.validate();
    assertEquals(t, mapper.readValue(mapper.writeValueAsBytes(t), Telemetry.class));
  }

  @Test
  void unavailableIsNullNotZero() throws Exception {
    var n = fixture().node;
    ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("health")).put("quality", "NO_CONTACT");
    assertThrows(
        IllegalArgumentException.class, () -> mapper.treeToValue(n, Telemetry.class).validate());
  }

  @Test
  void synchronizedTimestampIsRequired() throws Exception {
    var n = fixture().node;
    n.putNull("timestamp");
    assertThrows(
        IllegalArgumentException.class, () -> mapper.treeToValue(n, Telemetry.class).validate());
  }

  @Test
  void latitudeMustBeUnavailableWithoutFix() throws Exception {
    var n = fixture().node;
    ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("gps")).put("latitude", 0);
    assertThrows(
        IllegalArgumentException.class, () -> mapper.treeToValue(n, Telemetry.class).validate());
  }

  @Test
  void unsupportedVersionIsRejected() throws Exception {
    var n = fixture().node;
    n.put("protocolVersion", 2);
    assertThrows(
        IllegalArgumentException.class, () -> mapper.treeToValue(n, Telemetry.class).validate());
  }
}
