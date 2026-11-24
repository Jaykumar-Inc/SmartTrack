package org.smarttrack.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.smarttrack.dto.*;
import org.smarttrack.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  @GetMapping("/devices/{id}/motion")
  public Object motion(
      @PathVariable String id, @RequestParam(defaultValue = "120") int limit, Principal p) {
    return service.history(id, p.getName(), limit);
  }

  @GetMapping("/devices/{id}/health")
  public Object health(
      @PathVariable String id, @RequestParam(defaultValue = "120") int limit, Principal p) {
    return service.history(id, p.getName(), limit);
  }

  @GetMapping("/devices/{id}/settings")
  public Object configuration(@PathVariable String id, Principal p) {
    return service.configuration(id, p.getName());
  }

  @PutMapping("/devices/{id}/settings")
  public Object configure(
      @PathVariable String id, @Valid @RequestBody Requests.MotionSettings r, Principal p) {
    return service.configure(id, p.getName(), r);
  }

  @PostMapping("/devices/{id}/commands/{command}")
  public Object command(@PathVariable String id, @PathVariable String command, Principal p) {
    return service.command(id, p.getName(), command);
  }

  @GetMapping("/device-config/{id}")
  public Object deviceConfig(
      @PathVariable String id,
      @RequestHeader(value = "X-Device-Key", required = false) String key) {
    var c = service.deviceConfiguration(id, key);
    return Map.of(
        "sensitivity",
        c.getSensitivity(),
        "impactG",
        c.getImpactG(),
        "lowG",
        c.getLowG(),
        "inactivityMs",
        c.getInactivityMs(),
        "cooldownMs",
        c.getCooldownMs(),
        "cancelVersion",
        c.getCancelVersion(),
        "calibrateVersion",
        c.getCalibrateVersion(),
        "serverEpoch",
        java.time.Instant.now().getEpochSecond());
  }

  private final TrackingService service;
  private final UpdateHub hub;

  public ApiController(TrackingService s, UpdateHub h) {
    service = s;
    hub = h;
  }

  @GetMapping("/auth/me")
  public Object me(Principal p) {
    return Map.of("username", p.getName());
  }

  @GetMapping("/devices")
  public Object devices(Principal p) {
    return service.devices(p.getName());
  }

  @PostMapping("/devices")
  @ResponseStatus(HttpStatus.CREATED)
  public Object register(@Valid @RequestBody Requests.Registration r, Principal p) {
    return service.register(r, p.getName());
  }

  @PutMapping("/devices/{id}/thresholds")
  public Object thresholds(
      @PathVariable String id, @Valid @RequestBody Requests.Thresholds r, Principal p) {
    return service.thresholds(id, p.getName(), r);
  }

  @PostMapping("/ingest/{id}")
  public Object ingest(
      @PathVariable String id,
      @RequestHeader(value = "X-Device-Key", required = false) String key,
      @Valid @RequestBody Telemetry t) {
    return service.ingest(id, key, t);
  }

  @GetMapping("/devices/{id}/latest")
  public Object latest(@PathVariable String id, Principal p) {
    return service.latest(id, p.getName());
  }

  @GetMapping("/devices/{id}/history")
  public Object history(
      @PathVariable String id, @RequestParam(defaultValue = "120") int limit, Principal p) {
    return service.history(id, p.getName(), limit);
  }

  @GetMapping("/devices/{id}/temperature")
  public Object temperature(
      @PathVariable String id, @RequestParam(defaultValue = "500") int limit, Principal p) {
    return service.temperatureHistory(id, p.getName(), limit);
  }

  @GetMapping("/devices/{id}/alerts")
  public Object alerts(@PathVariable String id, Principal p) {
    return service.alerts(id, p.getName());
  }

  @PostMapping("/devices/{id}/alerts/{alertId}/ack")
  public Object acknowledge(@PathVariable String id, @PathVariable Long alertId, Principal p) {
    return service.acknowledge(id, p.getName(), alertId);
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Object events(Principal p) {
    return hub.subscribe(service.userId(p.getName()));
  }
}
