package org.smarttrack.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import javafx.collections.*;
import javafx.scene.chart.XYChart;

public final class MotionViewModel extends AbstractSensorViewModel {
  private JsonNode current;
  private final ObservableList<XYChart.Data<Number, Number>> magnitude =
      FXCollections.observableArrayList();
  private final ObservableList<String> impacts = FXCollections.observableArrayList();

  public String accelerationUnit() {
    return "g";
  }

  public void update(JsonNode current, JsonNode history) {
    this.current = current;
    quality.set(current.path("quality").asText("UNAVAILABLE"));
    magnitude.clear();
    impacts.clear();
    long previous = -1;
    for (int i = history.size() - 1; i >= 0; i--) {
      var row = history.get(i);
      var m = row.path("motion");
      if (m.path("magnitude").isNumber())
        magnitude.add(new XYChart.Data<>(history.size() - i - 1, m.path("magnitude").asDouble()));
      long count = m.path("impactCount").asLong();
      if (m.path("impact").asBoolean() && count != previous)
        impacts.add(
            row.path("receivedAt").asText()
                + " • Impact #"
                + count
                + " • "
                + m.path("fallState").asText());
      previous = count;
    }
  }

  public String reading(String field) {
    var number = current.path(field);
    return number.isNumber()
        ? String.format(Locale.ROOT, "%.3f", number.asDouble())
        : "Unavailable";
  }

  public ObservableList<XYChart.Data<Number, Number>> magnitude() {
    return magnitude;
  }

  public ObservableList<String> impacts() {
    return impacts;
  }
}
