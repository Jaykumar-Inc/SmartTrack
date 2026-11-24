package org.smarttrack.repository;

import org.smarttrack.entity.MotionReading;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MotionReadingRepository extends JpaRepository<MotionReading, Long> {
  java.util.List<MotionReading> findByReadingIdIn(java.util.Collection<Long> readingIds);

  java.util.Optional<MotionReading> findByReadingId(Long readingId);
}
