package org.smarttrack.service;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.smarttrack.dto.*;
import org.smarttrack.entity.*;
import org.smarttrack.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrackingService {
  private final AppUserRepository users;
  private final DeviceRepository devices;
  private final ReadingRepository readings;
  private final HealthReadingRepository health;
  private final MotionReadingRepository motion;
  private final TemperatureReadingRepository temperature;
  private final AlertRepository alerts;
  private final ConnectionEventRepository connections;
  private final PasswordEncoder passwords;
  private final List<AlertHandler> handlers;
  private final UpdateHub hub;
  private final DeviceSettingsRepository settings;
  private final AlertAcknowledgementRepository acknowledgements;

  public TrackingService(
      AppUserRepository u,
      DeviceRepository d,
      ReadingRepository r,
      HealthReadingRepository h,
      MotionReadingRepository m,
      TemperatureReadingRepository g,
      AlertRepository a,
      ConnectionEventRepository c,
      PasswordEncoder p,
      List<AlertHandler> handlers,
      UpdateHub hub,
      DeviceSettingsRepository settings,
      AlertAcknowledgementRepository acknowledgements) {
    this.settings = settings;
    this.acknowledgements = acknowledgements;
    users = u;
    devices = d;
    readings = r;
    health = h;
    motion = m;
    temperature = g;
    alerts = a;
    connections = c;
    passwords = p;
    this.handlers = handlers;
    this.hub = hub;
  }

  public Long userId(String name) {
    return users.findByUsername(name).orElseThrow(() -> error(401, "Invalid user")).getId();
  }

  public Device owned(String id, String name) {
    Device d = devices.findByDeviceId(id).orElseThrow(() -> error(404, "Device not found"));
    if (!d.getOwnerId().equals(userId(name))) throw error(404, "Device not found");
    return d;
  }

  public Map<String, Object> deviceView(Device d) {
    var v = new LinkedHashMap<String, Object>();
    v.put("deviceId", d.getDeviceId());
    v.put("name", d.getName());
    v.put("simulated", d.isSimulated());
    v.put("lastSeen", d.getLastSeen());
    v.put(
        "online",
        d.getLastSeen() != null && d.getLastSeen().isAfter(Instant.now().minusSeconds(10)));
    v.put("minBpm", d.getMinBpm());
    v.put("maxBpm", d.getMaxBpm());
    v.put("minSpo2", d.getMinSpo2());
    return v;
  }

  public List<Map<String, Object>> devices(String name) {
    return devices.findByOwnerId(userId(name)).stream().map(this::deviceView).toList();
  }

  @Transactional
  public Map<String, Object> register(Requests.Registration request, String name) {
    if (devices.findByDeviceId(request.deviceId()).isPresent())
      throw error(409, "Device ID already registered");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    Device d = new Device();
    d.setDeviceId(request.deviceId());
    d.setName(request.name());
    d.setOwnerId(userId(name));
    d.setSimulated(request.simulated());
    d.setKeyHash(passwords.encode(secret));
    devices.save(d);
    var configuration = new DeviceSettings();
    configuration.setDeviceId(d.getId());
    settings.save(configuration);
    var out = deviceView(d);
    out.put("apiKey", secret);
    return out;
  }

  @Transactional
  public Map<String, Object> thresholds(String id, String name, Requests.Thresholds t) {
    if (t.minBpm() >= t.maxBpm()) throw error(400, "Minimum BPM must be less than maximum");
    var d = owned(id, name);
    d.setMinBpm(t.minBpm());
    d.setMaxBpm(t.maxBpm());
    d.setMinSpo2(t.minSpo2());
    return deviceView(devices.save(d));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> ingest(String id, String key, Telemetry t) {
    Device d =
        devices.findByDeviceId(id).orElseThrow(() -> error(401, "Invalid device credentials"));
    if (key == null || !passwords.matches(key, d.getKeyHash()))
      throw error(401, "Invalid device credentials");
    return store(d, t);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> simulate(String id, Telemetry t) {
    var d = devices.findByDeviceId(id).orElseThrow();
    if (!d.isSimulated()) throw error(400, "Real device cannot simulate");
    return store(d, t);
  }

  private Map<String, Object> store(Device d, Telemetry t) {
    return store(d, t, t.simulated() ? "SIMULATED" : "WIFI");
  }

  private Map<String, Object> store(Device sourceDevice, Telemetry t, String transport) {
    // Serialize same-watch relay retries so duplicate check + insert is atomic.
    Device d = devices.lockByDeviceId(sourceDevice.getDeviceId()).orElseThrow();
    if (t.deviceId() != null && !t.deviceId().equals(d.getDeviceId()))
      throw error(400, "Payload deviceId differs from authenticated device");
    if (Boolean.FALSE.equals(t.health().valid())
        && (t.health().bpm() != null || t.health().spo2() != null))
      throw error(400, "Invalid health measurements must be null");
    if (t.motion().quality() != null
        && !"GOOD".equals(t.motion().quality())
        && (t.motion().ax() != null
            || t.motion().ay() != null
            || t.motion().az() != null
            || t.motion().gx() != null
            || t.motion().gy() != null
            || t.motion().gz() != null))
      throw error(400, "Unavailable/calibrating motion measurements must be null");
    if (d.isSimulated() != t.simulated())
      throw error(400, "Simulation flag must match device registration");
    var existing =
        readings.findByDeviceIdAndBootIdAndSequenceNo(d.getId(), t.bootId(), t.sequenceNo());
    if (existing.isPresent()) return view(existing.get());
    if (t.capturedAt() != null && t.capturedAt().isAfter(Instant.now().plusSeconds(60)))
      throw error(400, "Capture time is in the future");
    var temp = t.temperature();
    if ("GOOD".equals(temp.quality())
        && (temp.celsius() == null || temp.sampleAgeMs() == null || temp.sampleAgeMs() >= 3000))
      throw error(400, "Good chip temperature requires a fresh sample and value");
    if (!"GOOD".equals(temp.quality()) && temp.celsius() != null)
      throw error(400, "Invalid/stale chip temperature must be null");
    if ((!t.health().fingerPresent() || !"GOOD".equals(t.health().quality()))
        && (t.health().bpm() != null || t.health().spo2() != null))
      throw error(400, "Invalid optical signal must have null BPM and SpO2");
    var r = new Reading();
    r.setDeviceId(d.getId());
    r.setBootId(t.bootId());
    r.setSequenceNo(t.sequenceNo());
    r.setCapturedAt(t.capturedAt());
    r.setReceivedAt(Instant.now());
    r.setSimulated(t.simulated());
    r.setTransport(transport);
    r.setUptimeMs(t.uptimeMs());
    readings.saveAndFlush(r);
    var h = new HealthReading();
    h.setReadingId(r.getId());
    h.setBpm(t.health().bpm());
    h.setSpo2(t.health().spo2());
    h.setFingerPresent(t.health().fingerPresent());
    h.setQuality(t.health().quality());
    health.save(h);
    var m = new MotionReading();
    m.setReadingId(r.getId());
    m.setAx(t.motion().ax());
    m.setAy(t.motion().ay());
    m.setAz(t.motion().az());
    m.setGx(t.motion().gx());
    m.setGy(t.motion().gy());
    m.setGz(t.motion().gz());
    m.setTilt(t.motion().tilt());
    m.setPossibleFall(t.motion().possibleFall());
    m.setActivity(t.motion().activity());
    m.setPitch(t.motion().pitch());
    m.setRoll(t.motion().roll());
    m.setMagnitude(t.motion().magnitude());
    m.setTemperature(null); // Historical column retained; new channel is independent.
    m.setFallState(
        t.motion().fallState() == null
            ? (t.motion().possibleFall() ? "POSSIBLE_FALL" : "NORMAL")
            : t.motion().fallState());
    m.setQuality(
        t.motion().quality() == null
            ? (t.motion().ax() == null ? "UNAVAILABLE" : "GOOD")
            : t.motion().quality());
    m.setCalibration(t.motion().calibration() == null ? "UNVERIFIED" : t.motion().calibration());
    m.setEventId(t.motion().eventId());
    m.setImpactCount(t.motion().impactCount());
    m.setImpact(t.motion().impact());
    m.setInactive(t.motion().inactive());
    m.setAccelerationUnit("g");
    motion.save(m);
    var g = new TemperatureReading();
    g.setReadingId(r.getId());
    g.setCelsius(temp.celsius());
    g.setQuality(temp.quality());
    g.setSource(temp.source());
    g.setUnit(temp.unit());
    g.setSampleAgeMs(temp.sampleAgeMs());
    temperature.save(g);
    boolean fresh =
        t.capturedAt() == null || t.capturedAt().isAfter(Instant.now().minusSeconds(10));
    if (fresh) {
      if (!d.isConnected()) connection(d, true);
      d.setLastSeen(r.getReceivedAt());
      devices.save(d);
    }
    if (fresh)
      for (var handler : handlers)
        for (var candidate : handler.evaluate(d, t)) addReadingAlert(d, t, candidate);
    if (fresh && "UNAVAILABLE".equals(t.health().quality()))
      addAlert(
          d, "HEALTH_SENSOR", "WARNING", "MAX30102 unavailable or not producing fresh samples");
    if (fresh && "UNAVAILABLE".equals(t.motion().quality()))
      addAlert(d, "MOTION_SENSOR", "WARNING", "LSM6DS3 unavailable");
    if (fresh && !"GOOD".equals(temp.quality()))
      addAlert(
          d, "TEMPERATURE_SENSOR", "WARNING", "LSM6DS3 internal temperature unavailable or stale");
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            hub.publish(d.getOwnerId());
          }
        });
    return view(r);
  }

  public Map<String, Object> view(Reading r) {
    return view(
        r,
        health.findByReadingId(r.getId()).orElse(null),
        motion.findByReadingId(r.getId()).orElse(null),
        temperature.findByReadingId(r.getId()).orElse(null));
  }

  private Map<String, Object> view(
      Reading r, HealthReading h, MotionReading m, TemperatureReading g) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", r.getId());
    out.put("receivedAt", r.getReceivedAt());
    out.put("capturedAt", r.getCapturedAt());
    out.put("simulated", r.isSimulated());
    out.put("transport", r.getTransport());
    out.put("uptimeMs", r.getUptimeMs());
    out.put(
        "stale",
        r.getReceivedAt().isBefore(Instant.now().minusSeconds(10))
            || (r.getCapturedAt() != null
                && r.getCapturedAt().isBefore(Instant.now().minusSeconds(10))));
    out.put("health", h);
    out.put("motion", m);
    out.put("temperature", g);
    return out;
  }

  public List<Map<String, Object>> history(String id, String name, int limit) {
    var d = owned(id, name);
    var rows =
        readings.findByDeviceIdOrderByReceivedAtDesc(
            d.getId(), PageRequest.of(0, Math.clamp(limit, 1, 1000)));
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(Reading::getId).toList();
    var hs = indexByReadingId(health.findByReadingIdIn(ids), HealthReading::getReadingId);
    var ms = indexByReadingId(motion.findByReadingIdIn(ids), MotionReading::getReadingId);
    var gs = indexByReadingId(temperature.findByReadingIdIn(ids), TemperatureReading::getReadingId);
    return rows.stream()
        .map(r -> view(r, hs.get(r.getId()), ms.get(r.getId()), gs.get(r.getId())))
        .toList();
  }

  /**
   * A useful generic collection operation, shared across three independently typed sensor entities.
   */
  private static <T> Map<Long, T> indexByReadingId(
      List<T> values, java.util.function.Function<T, Long> key) {
    return values.stream()
        .collect(java.util.stream.Collectors.toMap(key, java.util.function.Function.identity()));
  }

  public Map<String, Object> latest(String id, String name) {
    return history(id, name, 1).stream()
        .findFirst()
        .orElseThrow(() -> error(404, "No readings yet"));
  }

  public List<Map<String, Object>> temperatureHistory(String id, String name, int limit) {
    return history(id, name, limit).stream()
        .filter(
            v ->
                v.get("temperature") instanceof TemperatureReading g
                    && "GOOD".equals(g.getQuality()))
        .toList();
  }

  public List<Alert> alerts(String id, String name) {
    return alerts.findByDeviceIdOrderByCreatedAtDesc(
        owned(id, name).getId(), PageRequest.of(0, 200));
  }

  @Transactional
  public Alert acknowledge(String id, String name, Long alertId) {
    var d = owned(id, name);
    var a =
        alerts
            .findById(alertId)
            .filter(x -> x.getDeviceId().equals(d.getId()))
            .orElseThrow(() -> error(404, "Alert not found"));
    if (a.getAcknowledgedAt() == null) {
      a.setAcknowledgedAt(Instant.now());
      a.setAcknowledgedBy(userId(name));
      var ack = new AlertAcknowledgement();
      ack.setAlertId(a.getId());
      ack.setUserId(userId(name));
      ack.setAcknowledgedAt(a.getAcknowledgedAt());
      acknowledgements.save(ack);
    }
    return alerts.save(a);
  }

  public void addAlert(Device d, String type, String severity, String message) {
    if (alerts.existsByDeviceIdAndTypeAndAcknowledgedAtIsNull(d.getId(), type)) return;
    var a = new Alert();
    a.setDeviceId(d.getId());
    a.setType(type);
    a.setSeverity(severity);
    a.setMessage(message);
    a.setCreatedAt(Instant.now());
    alerts.save(a);
  }

  private void connection(Device d, boolean state) {
    // Connection history is separate from sensor measurement quality.
    d.setConnected(state);
    var event = new ConnectionEvent();
    event.setDeviceId(d.getId());
    event.setConnected(state);
    event.setOccurredAt(Instant.now());
    connections.save(event);
  }

  @Transactional
  public void checkConnections() {
    for (var d : devices.findAll())
      if (d.isConnected()
          && (d.getLastSeen() == null
              || d.getLastSeen().isBefore(Instant.now().minusSeconds(10)))) {
        connection(d, false);
        devices.save(d);
        addAlert(d, "DISCONNECTED", "WARNING", "No telemetry for at least 10 seconds");
        hub.publish(d.getOwnerId());
      }
  }

  private static ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  private void addReadingAlert(Device d, Telemetry t, AlertHandler.Candidate c) {
    boolean event = c.type().contains("FALL") && t.motion().eventId() != null;
    if (!event) {
      addAlert(d, c.type(), c.severity(), c.message());
      return;
    }
    String key = t.bootId() + ":" + t.motion().eventId() + ":" + c.type();
    if (alerts.existsByDeviceIdAndEventKey(d.getId(), key)) return;
    var a = new Alert();
    a.setDeviceId(d.getId());
    a.setEventKey(key);
    a.setType(c.type());
    a.setSeverity(c.severity());
    a.setMessage(c.message());
    a.setCreatedAt(Instant.now());
    alerts.save(a);
  }

  public DeviceSettings configuration(String id, String user) {
    return settings.findByDeviceId(owned(id, user).getId()).orElseThrow();
  }

  public DeviceSettings deviceConfiguration(String id, String key) {
    var d = devices.findByDeviceId(id).orElseThrow(() -> error(401, "Invalid device credentials"));
    if (key == null || !passwords.matches(key, d.getKeyHash()))
      throw error(401, "Invalid device credentials");
    return settings.findByDeviceId(d.getId()).orElseThrow();
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public DeviceSettings configure(String id, String user, Requests.MotionSettings request) {
    devices.lockByDeviceId(owned(id, user).getDeviceId()).orElseThrow();
    var c = configuration(id, user);
    c.setSensitivity(request.sensitivity());
    c.setImpactG(request.impactG());
    c.setLowG(request.lowG());
    c.setInactivityMs(request.inactivityMs());
    c.setCooldownMs(request.cooldownMs());
    return settings.save(c);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public DeviceSettings command(String id, String user, String command) {
    devices.lockByDeviceId(owned(id, user).getDeviceId()).orElseThrow();
    var c = configuration(id, user);
    if (command.equals("cancel")) c.setCancelVersion(c.getCancelVersion() + 1);
    else if (command.equals("calibrate")) c.setCalibrateVersion(c.getCalibrateVersion() + 1);
    else throw error(400, "Unknown device command");
    return settings.save(c);
  }
}
