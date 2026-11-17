package io.smarttrack.backend.devices;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, String> {
  boolean existsByIdAndOwnerId(String id, String ownerId);
}
