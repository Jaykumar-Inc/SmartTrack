package org.smarttrack.repository;

import org.smarttrack.entity.ConnectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionEventRepository extends JpaRepository<ConnectionEvent, Long> {}
