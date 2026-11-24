package org.smarttrack.repository;

import java.util.Optional;
import org.smarttrack.entity.DeviceSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSettingsRepository extends JpaRepository<DeviceSettings, Long> {
  Optional<DeviceSettings> findByDeviceId(Long id);
}
