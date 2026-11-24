-- Old measurement history is retained. New temperature samples are never backfilled.
CREATE TABLE temperature_readings (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 reading_id BIGINT NOT NULL UNIQUE,
 celsius DOUBLE,
 quality VARCHAR(24) NOT NULL,
 source VARCHAR(32) NOT NULL,
 unit VARCHAR(4) NOT NULL,
 sample_age_ms BIGINT,
 CONSTRAINT fk_temperature_reading FOREIGN KEY(reading_id) REFERENCES sensor_readings(id) ON DELETE CASCADE
);
