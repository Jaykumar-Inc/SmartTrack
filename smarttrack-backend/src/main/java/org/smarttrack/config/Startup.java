package org.smarttrack.config;

import java.time.Instant;
import java.util.UUID;
import org.smarttrack.dto.*;
import org.smarttrack.entity.AppUser;
import org.smarttrack.repository.*;
import org.smarttrack.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Startup implements ApplicationRunner {
  private final AppUserRepository users;
  private final DeviceRepository devices;
  private final PasswordEncoder encoder;
  private final TrackingService service;

  @Value("${smarttrack.bootstrap-user}")
  private String username;

  @Value("${smarttrack.bootstrap-password}")
  private String password;

  @Value("${smarttrack.mock-enabled}")
  private boolean mock;

  private volatile String demoId;
  private long sequence;
  private long lastCancel, lastCalibrate, calibrateUntil, cancelUntil;
  private final String boot = UUID.randomUUID().toString();

  public Startup(AppUserRepository u, DeviceRepository d, PasswordEncoder e, TrackingService s) {
    users = u;
    devices = d;
    encoder = e;
    service = s;
  }

  public void run(ApplicationArguments args) {
    if (!username.isBlank() && users.findByUsername(username).isEmpty()) {
      if (password.length() < 12)
        throw new IllegalStateException("SMARTTRACK_PASSWORD must have at least 12 characters");
      var u = new AppUser();
      u.setUsername(username);
      u.setPasswordHash(encoder.encode(password));
      users.save(u);
    }
    if (mock) {
      if (username.isBlank())
        throw new IllegalStateException(
            "Mock mode requires SMARTTRACK_USER and SMARTTRACK_PASSWORD on first run");
      String registeredId = "demo-" + service.userId(username);
      if (devices.findByDeviceId(registeredId).isEmpty())
        service.register(
            new Requests.Registration(registeredId, "SIMULATED • Demo wearable", true), username);
      // Publish only after registration commits; the scheduled worker must not race creation.
      demoId = registeredId;
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void simulate() {
    if (!mock || demoId == null) return;
    double wave = Math.sin(sequence / 12.0);
    long n = sequence++, cycle = n % 90;
    var config = service.configuration(demoId, username);
    if (config.getCancelVersion() > lastCancel) {
      cancelUntil = n + 20;
      lastCancel = config.getCancelVersion();
    }
    if (config.getCalibrateVersion() > lastCalibrate) {
      calibrateUntil = n + 3;
      lastCalibrate = config.getCalibrateVersion();
    }
    boolean calibrating = n < calibrateUntil;
    String state =
        n < cancelUntil
            ? "RECOVERY"
            : cycle == 80
                ? "POSSIBLE_FALL"
                : cycle == 81
                    ? "VERIFYING"
                    : cycle >= 82 && cycle <= 86
                        ? "FALL_ALERT"
                        : cycle >= 87 ? "RECOVERY" : "NORMAL";
    boolean possible = java.util.Set.of("POSSIBLE_FALL", "VERIFYING", "FALL_ALERT").contains(state);
    var m =
        calibrating
            ? new Telemetry.Motion(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "UNAVAILABLE",
                null,
                null,
                null,
                null,
                "NORMAL",
                "CALIBRATING",
                "SIMULATED",
                n / 90,
                0L,
                false,
                false,
                "g")
            : new Telemetry.Motion(
                wave * .03,
                .02,
                1.0,
                .1,
                .3,
                .2,
                Math.abs(wave) * 5,
                possible,
                "MOVING",
                wave * 3,
                wave * 4,
                Math.sqrt(1.0004 + wave * wave * .0009),
                28.0,
                state,
                "GOOD",
                "SIMULATED",
                n / 90,
                n / 90 + (cycle >= 81 ? 1 : 0),
                cycle == 81,
                false,
                "g");
    service.simulate(
        demoId,
        new Telemetry(
            boot,
            n,
            Instant.now(),
            true,
            new Telemetry.Health(75 + wave * 8, 97 + wave, true, "GOOD"),
            m,
            new Telemetry.Temperature(28.0 + wave, "GOOD", "LSM6DS3_INTERNAL", "C", 0L),
            demoId,
            n * 1000));
  }

  @Scheduled(fixedDelay = 3000)
  public void connections() {
    service.checkConnections();
  }
}
