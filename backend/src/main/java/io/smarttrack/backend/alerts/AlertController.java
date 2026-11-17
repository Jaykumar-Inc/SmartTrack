package io.smarttrack.backend.alerts;

import static org.springframework.http.HttpStatus.*;

import io.smarttrack.backend.auth.Identity;
import io.smarttrack.backend.devices.DeviceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class AlertController {
  private final JdbcTemplate db;
  private final DeviceService devices;

  public AlertController(JdbcTemplate db, DeviceService devices) {
    this.db = db;
    this.devices = devices;
  }

  @GetMapping("/devices/{device}/alerts")
  public List<Map<String, Object>> alerts(
      @AuthenticationPrincipal Identity id, @PathVariable String device) {
    devices.userOnly(id);
    devices.authorize(id, device);
    return db.queryForList(
        "SELECT a.id,a.kind,a.severity,a.source,a.created_at,k.acknowledged_at FROM alert a LEFT"
            + " JOIN alert_acknowledgement k ON k.alert_id=a.id WHERE a.device_id=? ORDER BY"
            + " a.created_at DESC LIMIT 200",
        device);
  }

  @PostMapping("/alerts/{alert}/acknowledgement")
  @Transactional
  public Map<String, String> ack(@AuthenticationPrincipal Identity id, @PathVariable String alert) {
    devices.userOnly(id);
    var rows = db.queryForList("SELECT device_id FROM alert WHERE id=? FOR UPDATE", alert);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Alert unavailable");
    devices.authorize(id, (String) rows.getFirst().get("device_id"));
    if (db.queryForObject(
            "SELECT COUNT(*) FROM alert_acknowledgement WHERE alert_id=?", Integer.class, alert)
        == 0)
      db.update(
          "INSERT INTO alert_acknowledgement(alert_id,user_id,acknowledged_at) VALUES(?,?,?)",
          alert,
          id.userId(),
          Timestamp.from(Instant.now()));
    return Map.of("status", "acknowledged");
  }
}
