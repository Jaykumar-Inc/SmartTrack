package io.smarttrack.android.data;

import com.google.gson.*;
import java.util.*;
import retrofit2.Response;

/**
 * Shares only samples for devices owned by the current account. Never claims a device implicitly.
 */
public final class SyncEngine {
  private SyncEngine() {}

  public record Outcome(int sent, int waiting, String message, boolean retry) {}

  public static synchronized Outcome run(Vault vault, LocalDatabase db) {
    int waiting = db.readings().pendingCount();
    if (waiting == 0)
      return save(vault, new Outcome(0, 0, "All cached readings are shared", false));
    String token = vault.token(), address = BackendSettings.address(vault);
    if (token == null)
      return save(vault, new Outcome(0, waiting, "Sign in to share readings with the PC", false));
    try {
      Backend api = Backend.create(address, token);
      JsonArray devices = checked(api.devices().execute());
      List<String> owned = new ArrayList<>();
      for (var value : devices) owned.add(value.getAsJsonObject().get("id").getAsString());
      List<ReadingEntry> rows =
          owned.isEmpty() ? List.of() : db.readings().pendingForDevices(owned);
      if (rows.isEmpty())
        return save(
            vault,
            new Outcome(0, waiting, "Link the BLE device to your account before sharing", false));
      JsonArray payload = new JsonArray();
      for (var row : rows)
        payload.add(Wire.JSON.toJsonTree(Wire.decode(vault.decrypt(row.payload))));
      JsonArray result = checked(api.sync(payload).execute());
      if (result.size() != rows.size())
        throw new IllegalStateException(
            "Sharing acknowledgement is incomplete; cached data retained");
      if (!token.equals(vault.token()) || !address.equals(BackendSettings.address(vault)))
        return save(
            vault, new Outcome(0, waiting, "Account changed; cached readings retained", true));
      for (var row : rows) db.readings().synced(row.id);
      waiting = db.readings().pendingCount();
      return save(
          vault,
          new Outcome(
              rows.size(),
              waiting,
              "Shared " + rows.size() + " readings; " + waiting + " waiting",
              rows.size() == 100));
    } catch (Exception failure) {
      String message = io.smarttrack.contract.BackendEndpoint.failure(failure, address);
      return save(
          vault, new Outcome(0, waiting, message, !(failure instanceof IllegalArgumentException)));
    }
  }

  private static Outcome save(Vault vault, Outcome outcome) {
    vault.preferences().edit().putString("last_sync_message", outcome.message()).apply();
    return outcome;
  }

  private static <T> T checked(Response<T> response) throws Exception {
    if (!response.isSuccessful()) {
      String detail = "Sharing failed (" + response.code() + ")";
      if (response.errorBody() != null) {
        try {
          detail =
              JsonParser.parseString(response.errorBody().string())
                  .getAsJsonObject()
                  .get("detail")
                  .getAsString();
        } catch (RuntimeException ignored) {
        }
      }
      if (response.code() == 401) detail = "Session expired. Sign in again to share readings";
      throw new IllegalStateException(detail);
    }
    if (response.body() == null) throw new IllegalStateException("Empty backend response");
    return response.body();
  }
}
