package org.smarttrack.repository;

import org.smarttrack.entity.HealthReading;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthReadingRepository extends JpaRepository<HealthReading, Long> {
  java.util.List<HealthReading> findByReadingIdIn(java.util.Collection<Long> readingIds);

  java.util.Optional<HealthReading> findByReadingId(Long readingId);
}
