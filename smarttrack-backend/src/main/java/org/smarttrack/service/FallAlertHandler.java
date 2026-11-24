package org.smarttrack.service;

import java.util.*;
import org.smarttrack.dto.Telemetry;
import org.smarttrack.entity.Device;
import org.springframework.stereotype.Component;

@Component
public class FallAlertHandler implements AlertHandler {
  public List<Candidate> evaluate(Device d, Telemetry t) {
    if (t.motion().quality() != null && !"GOOD".equals(t.motion().quality())) return List.of();
    if ("FALL_ALERT".equals(t.motion().fallState()))
      return List.of(
          new Candidate(
              "ALGORITHMIC_FALL",
              "HIGH",
              "Fall algorithm criteria met. Check wearer; this is not proof of a real fall."));
    if (t.motion().fallState() != null
        && !Set.of("POSSIBLE_FALL", "VERIFYING").contains(t.motion().fallState())) return List.of();
    return t.motion().possibleFall()
        ? List.of(
            new Candidate(
                "POSSIBLE_FALL",
                "HIGH",
                "Possible fall detected. Check the wearer; experimental detection."))
        : List.of();
  }
}
