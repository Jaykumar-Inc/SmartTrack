package io.smarttrack.android.data;

import android.os.Build;
import io.smarttrack.contract.BackendEndpoint;
import java.util.Locale;

public final class BackendSettings {
  private BackendSettings() {}

  public static boolean emulator() {
    String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
    return fingerprint.contains("generic")
        || fingerprint.contains("emulator")
        || Build.MODEL.contains("sdk_gphone")
        || Build.HARDWARE.equals("ranchu")
        || Build.HARDWARE.equals("goldfish");
  }

  public static String address(Vault vault) {
    String saved = vault.preferences().getString("backend", "");
    if (saved.equals("http://10.0.2.2:8080")) saved = BackendEndpoint.EMULATOR;
    if (saved.equals("http://127.0.0.1:8080")) saved = BackendEndpoint.DESKTOP;
    if (!emulator() && saved.equals(BackendEndpoint.EMULATOR)) return "";
    return saved.isEmpty() && emulator() ? BackendEndpoint.EMULATOR : saved;
  }
}
