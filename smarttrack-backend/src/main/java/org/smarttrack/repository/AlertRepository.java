package org.smarttrack.repository;

import org.smarttrack.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {
  boolean existsByDeviceIdAndEventKey(Long deviceId, String eventKey);

  java.util.List<Alert> findByDeviceIdOrderByCreatedAtDesc(
      Long deviceId, org.springframework.data.domain.Pageable page);

  boolean existsByDeviceIdAndTypeAndAcknowledgedAtIsNull(Long deviceId, String type);
}
