package org.smarttrack.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Wi-Fi HTTP client for the shared Spring Boot backend. No device pairing. */
final class ApiClient {
  interface Result {
    void done(Object value, Exception error);
  }

  private final String base, auth;
  private final ExecutorService operations = Executors.newFixedThreadPool(2);
  private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
  private final AtomicInteger generation = new AtomicInteger();
  private volatile boolean closed, healthy;
  private volatile String lastError = "Connect to the Wi-Fi server";
  private volatile long lastSuccess, latencyMs;
  private final AtomicInteger failures = new AtomicInteger();

  ApiClient(String url, String username, String password) {
    String value = url.trim();
    if (!value.contains("://")) value = "http://" + value;
    URI parsed = URI.create(value);
    if ((!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme()))
        || parsed.getHost() == null
        || parsed.getUserInfo() != null
        || parsed.getQuery() != null
        || parsed.getFragment() != null
        || (parsed.getPath() != null
            && !parsed.getPath().isEmpty()
            && !parsed.getPath().equals("/")))
      throw new IllegalArgumentException("Enter the server URL from SHOW_LOGIN.cmd");
    base = value.replaceAll("/+$", "");
    auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  boolean isConnected() {
    return healthy
        && !closed
        && lastSuccess != 0
        && System.nanoTime() - lastSuccess < TimeUnit.SECONDS.toNanos(15);
  }

  boolean isConnectedTo(String id) {
    return isConnected() && id != null;
  }

  String connectionStatus() {
    return healthy ? "Wi-Fi server connected | shared history" : lastError;
  }

  void updates(Runnable listener) {} // The activity's existing timer polls the shared server.

  String diagnostics() {
    long age =
        lastSuccess == 0 ? -1 : TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastSuccess);
    return "Server: "
        + base
        + "\n"
        + connectionStatus()
        + "\nLast successful response: "
        + (age < 0 ? "none" : age + " seconds ago")
        + "\nResponse latency: "
        + latencyMs
        + " ms | Consecutive failed requests: "
        + failures.get()
        + "\nPhone and desktop use the same account and database.";
  }

  void reconnectNow() {
    request("GET", "/auth/me", null, (value, error) -> {});
  }

  void request(String method, String path, JSONObject body, Result callback) {
    if (closed) {
      callback.done(null, new IOException("Connection closed"));
      return;
    }
    int token = generation.get();
    operations.submit(
        () -> {
          HttpURLConnection connection = null;
          long started = System.nanoTime();
          try {
            if (closed || token != generation.get()) throw new IOException("Request cancelled");
            connection =
                (HttpURLConnection) URI.create(base + "/api" + path).toURL().openConnection();
            active.add(connection);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", auth);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null || method.equals("POST") || method.equals("PUT")) {
              byte[] bytes = (body == null ? "" : body.toString()).getBytes(StandardCharsets.UTF_8);
              connection.setDoOutput(true);
              connection.setRequestProperty("Content-Type", "application/json");
              connection.setFixedLengthStreamingMode(bytes.length);
              try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
              }
            }
            int status = connection.getResponseCode();
            InputStream stream =
                status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            StringBuilder text = new StringBuilder();
            if (stream != null)
              try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] bytes = new char[4096];
                int count;
                while ((count = reader.read(bytes)) != -1) {
                  if (text.length() + count > 2 * 1024 * 1024)
                    throw new IOException("Server response too large");
                  text.append(bytes, 0, count);
                }
              }
            if (status < 200 || status >= 300)
              throw new IOException(
                  status == 401
                      ? "Incorrect username or password"
                      : "Server returned HTTP " + status);
            if (closed || token != generation.get()) return;
            healthy = true;
            latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            failures.set(0);
            lastSuccess = System.nanoTime();
            callback.done(new JSONTokener(text.toString()).nextValue(), null);
          } catch (Exception error) {
            if (token == generation.get() && !closed) {
              healthy = false;
              failures.incrementAndGet();
              lastError = "Server unavailable: " + error.getMessage();
              callback.done(null, error);
            }
          } finally {
            if (connection != null) {
              active.remove(connection);
              connection.disconnect();
            }
          }
        });
  }

  void cancel() {
    generation.incrementAndGet();
    healthy = false;
    for (HttpURLConnection connection : active) connection.disconnect();
  }

  void close() {
    closed = true;
    cancel();
    operations.shutdownNow();
  }
}
