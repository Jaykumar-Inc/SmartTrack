package org.smarttrack.repository;

import org.smarttrack.entity.TemperatureReading;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemperatureReadingRepository extends JpaRepository<TemperatureReading, Long> {
  java.util.List<TemperatureReading> findByReadingIdIn(java.util.Collection<Long> ids);

  java.util.Optional<TemperatureReading> findByReadingId(Long readingId);
}
