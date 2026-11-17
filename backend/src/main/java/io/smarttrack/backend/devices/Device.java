package io.smarttrack.backend.devices;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device")
public class Device {
  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "owner_id", nullable = false, length = 36)
  private String ownerId;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(name = "secret_hash", nullable = false, length = 64)
  private String secretHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Device() {}

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
