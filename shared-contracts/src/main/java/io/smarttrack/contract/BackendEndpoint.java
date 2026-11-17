package io.smarttrack.contract;

import java.net.URI;

/** Addresses shared by local launchers and clients. */
public final class BackendEndpoint {
  public static final String DESKTOP = "http://127.0.0.1:8087";
  public static final String EMULATOR = "http://10.0.2.2:8087";

  private BackendEndpoint() {}

  public static String normalize(String address) {
    if (address == null || address.trim().isEmpty())
      throw new IllegalArgumentException("Enter the backend address shown by Start-Backend.cmd");
    String value = address.trim().replaceAll("/+$", "");
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Enter a full backend URL, for example " + DESKTOP);
    }
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535)
      throw new IllegalArgumentException(
          "Enter a full HTTP(S) backend URL without credentials or a query");
    return value;
  }

  public static String failure(Throwable error, String address) {
    Throwable cause = error;
    while (cause.getCause() != null) cause = cause.getCause();
    if (cause instanceof javax.net.ssl.SSLException)
      return "TLS connection failed. Check the backend certificate and device date/time.";
    if (cause instanceof java.net.UnknownHostException)
      return "Backend hostname not found. Check the address and network connection.";
    if (cause instanceof java.io.IOException)
      return "Cannot reach "
          + address
          + ". Start Start-Backend.cmd and test the connection. "
          + "On a phone, use the laptop's LAN address on the same network.";
    return cause.getMessage() == null
        ? "Connection failed. Test the backend connection."
        : cause.getMessage();
  }
}
