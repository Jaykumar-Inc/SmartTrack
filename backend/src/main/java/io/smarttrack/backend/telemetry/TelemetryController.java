package io.smarttrack.backend.telemetry;

import io.smarttrack.backend.auth.Identity;
import io.smarttrack.contract.Telemetry;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class TelemetryController {
  private final TelemetryService service;

  public TelemetryController(TelemetryService service) {
    this.service = service;
  }

  @PostMapping("/telemetry")
  public TelemetryService.Accepted ingest(
      @AuthenticationPrincipal Identity id, @RequestBody Telemetry t) {
    return service.ingest(id, t);
  }

  @PostMapping("/synchronization")
  public List<TelemetryService.Accepted> sync(
      @AuthenticationPrincipal Identity id, @RequestBody List<Telemetry> values) {
    if (values.isEmpty() || values.size() > 100)
      throw new IllegalArgumentException("Sync requires 1..100 readings");
    return values.stream().map(t -> service.ingest(id, t)).toList();
  }

  @GetMapping("/devices/{device}/latest")
  public TelemetryService.Reading latest(
      @AuthenticationPrincipal Identity id, @PathVariable String device) {
    return service.latest(id, device);
  }

  @GetMapping("/devices/{device}/history")
  public List<TelemetryService.Reading> history(
      @AuthenticationPrincipal Identity id,
      @PathVariable String device,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "500") int limit) {
    return service.history(id, device, from, to, limit);
  }

  @GetMapping("/devices/{device}/synchronization")
  public List<Map<String, Object>> state(
      @AuthenticationPrincipal Identity id, @PathVariable String device) {
    return service.syncState(id, device);
  }
}
