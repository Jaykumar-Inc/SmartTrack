package org.smarttrack.repository;

import org.smarttrack.entity.Reading;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingRepository extends JpaRepository<Reading, Long> {
  java.util.Optional<Reading> findByDeviceIdAndBootIdAndSequenceNo(
      Long deviceId, String bootId, long sequenceNo);

  java.util.List<Reading> findByDeviceIdOrderByReceivedAtDesc(
      Long deviceId, org.springframework.data.domain.Pageable page);
}
