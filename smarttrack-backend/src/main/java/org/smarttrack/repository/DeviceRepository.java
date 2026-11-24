package org.smarttrack.repository;

import org.smarttrack.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select d from Device d where d.deviceId = :id")
  java.util.Optional<Device> lockByDeviceId(
      @org.springframework.data.repository.query.Param("id") String id);

  java.util.List<Device> findByOwnerId(Long ownerId);

  java.util.Optional<Device> findByDeviceId(String deviceId);
}
