package io.smarttrack.backend.common;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RetentionJob {
  private final JdbcTemplate db;
  private final int days;

  public RetentionJob(JdbcTemplate db, @Value("${smarttrack.retention-days:30}") int days) {
    if (days < 1 || days > 3650)
      throw new IllegalArgumentException("retention-days must be 1..3650");
    this.db = db;
    this.days = days;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  @Transactional
  public void expire() {
    Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    db.update("DELETE FROM alert WHERE created_at<?", cutoff);
    db.update("DELETE FROM telemetry_batch WHERE received_at<?", cutoff);
    db.update("DELETE FROM device_session WHERE last_seen<?", cutoff);
    db.update("DELETE FROM access_token WHERE expires_at<?", Timestamp.from(Instant.now()));
  }
}
