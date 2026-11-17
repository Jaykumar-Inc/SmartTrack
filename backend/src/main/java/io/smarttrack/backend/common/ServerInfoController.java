package io.smarttrack.backend.common;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class ServerInfoController {
  @GetMapping("/v1/info")
  public Map<String, Object> info() {
    return Map.of(
        "application", "io.smarttrack.backend", "version", "1.1.0", "telemetryProtocolVersion", 1);
  }
}
