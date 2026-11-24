package org.smarttrack.desktop;

import java.util.prefs.Preferences;

/** Local starter entry point; only non-secret login hints are persisted. */
public final class DesktopLauncher {
  public static void main(String[] args) {
    var preferences = Preferences.userNodeForPackage(SmartTrackDesktop.class);
    var backend = System.getenv("SMARTTRACK_BACKEND_URL");
    var username = System.getenv("SMARTTRACK_LOGIN_USER");
    if (backend != null && !backend.isBlank()) preferences.put("backend", backend);
    if (username != null && !username.isBlank()) preferences.put("username", username);
    SmartTrackDesktop.main(args);
  }
}
