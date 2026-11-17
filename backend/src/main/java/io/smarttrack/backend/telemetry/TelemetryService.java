package io.smarttrack.backend.telemetry;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smarttrack.backend.auth.Identity;
import io.smarttrack.backend.devices.DeviceService;
import io.smarttrack.contract.Telemetry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelemetryService {
  public record Reading(String id, Instant receivedAt, String freshness, Telemetry telemetry) {}

  public record Accepted(String id, boolean duplicate) {}

  public record Committed(String ownerId, String deviceId, Reading reading) {}

  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final DeviceService devices;
  private final ApplicationEventPublisher events;

  @Value("${smarttrack.allow-simulator:false}")
  private boolean allowSimulator;

  public TelemetryService(
      JdbcTemplate db,
      ObjectMapper mapper,
      DeviceService devices,
      ApplicationEventPublisher events) {
    this.db = db;
    this.mapper = mapper;
    this.devices = devices;
    this.events = events;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Accepted ingest(Identity identity, Telemetry t) {
    if (t == null) throw new IllegalArgumentException("Missing telemetry snapshot");
    t.validate();
    devices.authorize(identity, t.deviceId());
    if (t.source() == Telemetry.Source.SIMULATOR && !allowSimulator)
      throw new ResponseStatusException(FORBIDDEN, "Simulator ingestion disabled");
    Instant now = Instant.now();
    if (t.timestamp() != null && t.timestamp().isAfter(now.plusSeconds(120)))
      throw new IllegalArgumentException("Timestamp is in the future");
    // Serialize per device across all transport sessions; duplicate checks and inserts share the
    // lock.
    db.queryForObject("SELECT id FROM device WHERE id=? FOR UPDATE", String.class, t.deviceId());
    String payload = encode(t);
    var existing =
        db.queryForList(
            "SELECT id,payload FROM telemetry_batch WHERE device_id=? AND session_id=? AND"
                + " sequence_no=?",
            t.deviceId(),
            t.sessionId(),
            t.sequence());
    if (!existing.isEmpty()) {
      if (!existing.getFirst().get("payload").equals(payload))
        throw new ResponseStatusException(
            CONFLICT, "Sequence already contains different telemetry");
      return new Accepted((String) existing.getFirst().get("id"), true);
    }
    Timestamp received = Timestamp.from(now);
    int updated =
        db.update(
            "UPDATE device_session SET last_seen=? WHERE device_id=? AND id=?",
            received,
            t.deviceId(),
            t.sessionId());
    if (updated == 0)
      db.update(
          "INSERT INTO device_session(device_id,id,first_seen,last_seen) VALUES(?,?,?,?)",
          t.deviceId(),
          t.sessionId(),
          received,
          received);
    String batch = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " telemetry_batch(id,device_id,session_id,sequence_no,measured_at,received_at,source,payload)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        batch,
        t.deviceId(),
        t.sessionId(),
        t.sequence(),
        t.timestamp() == null ? null : Timestamp.from(t.timestamp()),
        received,
        t.source().name(),
        payload);
    var h = t.health();
    db.update(
        "INSERT INTO health_reading(batch_id,heart_rate_bpm,spo2_percent,quality) VALUES(?,?,?,?)",
        batch,
        h.heartRateBpm(),
        h.spo2Percent(),
        h.quality().name());
    var m = t.motion();
    db.update(
        "INSERT INTO motion_reading(batch_id,magnitude_g,pitch_deg,roll_deg,activity,fall_state)"
            + " VALUES(?,?,?,?,?,?)",
        batch,
        m.magnitudeG(),
        m.pitchDeg(),
        m.rollDeg(),
        m.activity().name(),
        m.fallState().name());
    var g = t.gps();
    db.update(
        "INSERT INTO gps_reading(batch_id,has_fix,latitude,longitude,satellites) VALUES(?,?,?,?,?)",
        batch,
        g.fix(),
        g.latitude(),
        g.longitude(),
        g.satellites());
    for (var alert : t.alerts())
      db.update(
          "INSERT INTO alert(id,device_id,batch_id,kind,severity,source,created_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          t.deviceId(),
          batch,
          alert.kind().name(),
          alert.severity().name(),
          t.source().name(),
          received);
    int sync =
        db.update(
            "UPDATE synchronization_state SET highest_sequence=CASE WHEN highest_sequence<? THEN ?"
                + " ELSE highest_sequence END,updated_at=? WHERE device_id=? AND session_id=?",
            t.sequence(),
            t.sequence(),
            received,
            t.deviceId(),
            t.sessionId());
    if (sync == 0)
      db.update(
          "INSERT INTO synchronization_state(device_id,session_id,highest_sequence,updated_at)"
              + " VALUES(?,?,?,?)",
          t.deviceId(),
          t.sessionId(),
          t.sequence(),
          received);
    events.publishEvent(new Committed(identity.userId(), t.deviceId(), reading(batch, now, t)));
    return new Accepted(batch, false);
  }

  public Reading latest(Identity id, String device) {
    devices.authorize(id, device);
    return db
        .query(
            "SELECT id,received_at,payload FROM telemetry_batch WHERE device_id=? ORDER BY"
                + " COALESCE(measured_at,received_at) DESC,received_at DESC LIMIT 1",
            (rs, n) ->
                reading(rs.getString(1), rs.getTimestamp(2).toInstant(), decode(rs.getString(3))),
            device)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No telemetry yet"));
  }

  public List<Reading> history(Identity id, String device, Instant from, Instant to, int limit) {
    devices.authorize(id, device);
    if (!from.isBefore(to) || Duration.between(from, to).toDays() > 31 || limit < 1 || limit > 2000)
      throw new IllegalArgumentException("Use a range up to 31 days and limit 1..2000");
    return db.query(
        "SELECT id,received_at,payload FROM telemetry_batch WHERE device_id=? AND received_at>=?"
            + " AND received_at<? ORDER BY received_at DESC,id DESC LIMIT ?",
        (rs, n) ->
            reading(rs.getString(1), rs.getTimestamp(2).toInstant(), decode(rs.getString(3))),
        device,
        Timestamp.from(from),
        Timestamp.from(to),
        limit);
  }

  public List<Map<String, Object>> syncState(Identity id, String device) {
    devices.authorize(id, device);
    return db.queryForList(
        "SELECT session_id,highest_sequence,updated_at FROM synchronization_state WHERE"
            + " device_id=?",
        device);
  }

  private Reading reading(String id, Instant received, Telemetry t) {
    return new Reading(id, received, freshness(t, received, Instant.now()), t);
  }

  public static String freshness(Telemetry t, Instant received, Instant now) {
    if (t.source() == Telemetry.Source.SIMULATOR) return "SIMULATED";
    if (Duration.between(received, now).getSeconds() > 15) return "OFFLINE";
    if (t.timestamp() == null) return "TIME_UNVERIFIED";
    return Duration.between(t.timestamp(), now).getSeconds() > 10 ? "DELAYED" : "LIVE";
  }

  public String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialize telemetry");
    }
  }

  private Telemetry decode(String payload) {
    try {
      return mapper.readValue(payload, Telemetry.class);
    } catch (Exception e) {
      throw new IllegalStateException("Stored contract is invalid", e);
    }
  }
}
