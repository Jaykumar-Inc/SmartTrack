package io.smarttrack.backend.devices;

import static org.springframework.http.HttpStatus.*;

import io.smarttrack.backend.auth.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceService {
  private final JdbcTemplate db;
  private final DeviceRepository registry;

  public DeviceService(JdbcTemplate db, DeviceRepository registry) {
    this.db = db;
    this.registry = registry;
  }

  public void userOnly(Identity id) {
    if (id.isDevice()) throw new ResponseStatusException(FORBIDDEN, "User credentials required");
  }

  public void authorize(Identity id, String deviceId) {
    if (id.isDevice() && !id.deviceId().equals(deviceId))
      throw new ResponseStatusException(FORBIDDEN, "Device credentials are scoped to one device");
    if (!registry.existsByIdAndOwnerId(deviceId, id.userId()))
      throw new ResponseStatusException(NOT_FOUND, "Device unavailable");
  }

  @Transactional
  public Map<String, String> register(Identity id, String deviceId, String name) {
    userOnly(id);
    String secret = AuthService.secret("dev_");
    db.update(
        "INSERT INTO device(id,owner_id,name,secret_hash,created_at) VALUES(?,?,?,?,?)",
        deviceId,
        id.userId(),
        name,
        AuthService.hash(secret),
        Timestamp.from(Instant.now()));
    db.update(
        "INSERT INTO device_configuration(device_id,revision,telemetry_interval_ms,timezone)"
            + " VALUES(?,0,1000,'Asia/Kolkata')",
        deviceId);
    return Map.of("id", deviceId, "name", name, "deviceToken", secret);
  }

  public List<Map<String, Object>> list(Identity id) {
    userOnly(id);
    return db.queryForList(
        "SELECT d.id,d.name,d.created_at,MAX(t.received_at) AS last_received_at FROM device d LEFT"
            + " JOIN telemetry_batch t ON t.device_id=d.id WHERE d.owner_id=? GROUP BY"
            + " d.id,d.name,d.created_at ORDER BY d.name",
        id.userId());
  }

  public Map<String, Object> configuration(Identity id, String deviceId) {
    authorize(id, deviceId);
    return db.queryForMap(
        "SELECT revision,telemetry_interval_ms,timezone FROM device_configuration WHERE"
            + " device_id=?",
        deviceId);
  }

  @Transactional
  public Map<String, Object> configure(
      Identity id, String deviceId, long revision, int interval, String timezone) {
    userOnly(id);
    authorize(id, deviceId);
    java.time.ZoneId.of(timezone);
    if (db.update(
            "UPDATE device_configuration SET revision=revision+1,telemetry_interval_ms=?,timezone=?"
                + " WHERE device_id=? AND revision=?",
            interval,
            timezone,
            deviceId,
            revision)
        != 1)
      throw new ResponseStatusException(CONFLICT, "Configuration changed; reload before saving");
    return configuration(id, deviceId);
  }

  @Transactional
  public Map<String, String> rotate(Identity id, String deviceId) {
    userOnly(id);
    authorize(id, deviceId);
    String secret = AuthService.secret("dev_");
    db.update("UPDATE device SET secret_hash=? WHERE id=?", AuthService.hash(secret), deviceId);
    return Map.of("deviceToken", secret);
  }
}
