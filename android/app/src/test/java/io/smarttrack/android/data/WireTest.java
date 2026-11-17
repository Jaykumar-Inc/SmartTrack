package io.smarttrack.android.data;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

public class WireTest {
  @Test
  public void canonicalContractUsesIdenticalFieldNames() throws Exception {
    String value =
        new String(
            Files.readAllBytes(Paths.get("../../shared-contracts/example-simulator.json")),
            java.nio.charset.StandardCharsets.UTF_8);
    var t = Wire.decode(value);
    assertEquals(t, Wire.decode(Wire.JSON.toJson(t)));
    assertEquals("SIMULATOR", t.source().name());
  }
}
