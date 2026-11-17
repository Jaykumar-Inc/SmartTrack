package io.smarttrack.android.data;

import android.content.Context;
import android.net.*;
import java.net.Inet4Address;
import java.util.stream.Collectors;

public final class NetworkDiagnostics {
  private NetworkDiagnostics() {}

  public static String describe(Context context) {
    try {
      ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
      Network network = manager == null ? null : manager.getActiveNetwork();
      NetworkCapabilities capabilities =
          network == null ? null : manager.getNetworkCapabilities(network);
      if (capabilities == null) return "Network: no active connection";
      String type =
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
              ? "VPN"
              : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                  ? "Wi-Fi"
                  : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                      ? "Mobile data"
                      : "Other";
      LinkProperties properties = manager.getLinkProperties(network);
      String addresses =
          properties == null
              ? "unavailable"
              : properties.getLinkAddresses().stream()
                  .filter(address -> address.getAddress() instanceof Inet4Address)
                  .map(address -> address.getAddress().getHostAddress())
                  .collect(Collectors.joining(", "));
      return "Network: "
          + type
          + "\nPhone IPv4: "
          + (addresses.isEmpty() ? "unavailable" : addresses)
          + "\nAndroid API: "
          + android.os.Build.VERSION.SDK_INT;
    } catch (SecurityException denied) {
      return "Network details unavailable";
    }
  }
}
