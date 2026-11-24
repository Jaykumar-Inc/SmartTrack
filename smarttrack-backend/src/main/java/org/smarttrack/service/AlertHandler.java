package org.smarttrack.service;

import java.util.List;
import org.smarttrack.dto.Telemetry;
import org.smarttrack.entity.Device;

/**
 * Interface and polymorphism allow independent alert strategies without artificial sensor
 * inheritance.
 */
public interface AlertHandler {
  record Candidate(String type, String severity, String message) {}

  List<Candidate> evaluate(Device device, Telemetry reading);
}
