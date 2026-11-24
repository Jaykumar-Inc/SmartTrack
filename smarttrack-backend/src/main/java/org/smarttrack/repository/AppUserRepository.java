package org.smarttrack.repository;

import org.smarttrack.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  java.util.Optional<AppUser> findByUsername(String username);
}
