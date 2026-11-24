package org.smarttrack.service;

import java.util.*;
import org.smarttrack.dto.Telemetry;
import org.smarttrack.entity.Device;
import org.springframework.stereotype.Component;

@Component
public class HealthAlertHandler implements AlertHandler {
  public List<Candidate> evaluate(Device d, Telemetry t) {
    var out = new ArrayList<Candidate>();
    var h = t.health();
    if (h.fingerPresent() && "GOOD".equals(h.quality())) {
      if (h.bpm() != null && (h.bpm() < d.getMinBpm() || h.bpm() > d.getMaxBpm()))
        out.add(new Candidate("HEART_RATE", "WARNING", "Heart rate outside configured range"));
      if (h.spo2() != null && h.spo2() < d.getMinSpo2())
        out.add(new Candidate("SPO2", "WARNING", "Estimated SpO2 below configured threshold"));
    }
    return out;
  }
}
