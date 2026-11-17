package io.smarttrack.android.data;

import com.google.gson.*;
import io.smarttrack.contract.Telemetry;
import java.time.Instant;

public final class Wire {
  public static final Gson JSON =
      new GsonBuilder()
          .serializeNulls()
          .registerTypeAdapter(
              Instant.class,
              (JsonSerializer<Instant>) (value, type, ctx) -> new JsonPrimitive(value.toString()))
          .registerTypeAdapter(
              Instant.class,
              (JsonDeserializer<Instant>) (value, type, ctx) -> Instant.parse(value.getAsString()))
          .create();

  private Wire() {}

  public static Telemetry decode(String value) {
    Telemetry t = JSON.fromJson(value, Telemetry.class);
    t.validate();
    return t;
  }
}
