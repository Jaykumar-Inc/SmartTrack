package io.smarttrack.backend.devices;

import io.smarttrack.backend.auth.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/devices")
public class DeviceController {
  public record Registration(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,64}") String id,
      @NotBlank @Size(max = 80) String name) {}

  public record Configuration(
      @Min(0) long revision,
      @Min(1000) @Max(60000) int telemetryIntervalMs,
      @NotBlank @Size(max = 64) String timezone) {}

  private final DeviceService service;

  public DeviceController(DeviceService service) {
    this.service = service;
  }

  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal Identity id) {
    return service.list(id);
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, String> register(
      @AuthenticationPrincipal Identity id, @Valid @RequestBody Registration r) {
    return service.register(id, r.id(), r.name());
  }

  @GetMapping("/{device}/configuration")
  public Map<String, Object> configuration(
      @AuthenticationPrincipal Identity id, @PathVariable String device) {
    return service.configuration(id, device);
  }

  @PutMapping("/{device}/configuration")
  public Map<String, Object> configuration(
      @AuthenticationPrincipal Identity id,
      @PathVariable String device,
      @Valid @RequestBody Configuration c) {
    return service.configure(id, device, c.revision(), c.telemetryIntervalMs(), c.timezone());
  }

  @PostMapping("/{device}/rotate-token")
  public Map<String, String> rotate(
      @AuthenticationPrincipal Identity id, @PathVariable String device) {
    return service.rotate(id, device);
  }
}
