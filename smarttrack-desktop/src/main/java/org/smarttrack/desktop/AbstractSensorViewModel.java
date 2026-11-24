package org.smarttrack.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.*;

/**
 * Useful abstract base: shared nullable value formatting and observable quality for sensor panels.
 */
public abstract class AbstractSensorViewModel {
  protected final StringProperty quality = new SimpleStringProperty("UNAVAILABLE");

  public ReadOnlyStringProperty qualityProperty() {
    return quality;
  }

  public abstract String accelerationUnit();

  protected String value(JsonNode node, String field) {
    return ReadingState.value(node, field, "");
  }
}
