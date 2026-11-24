package org.smarttrack.desktop;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class ApiClient implements AutoCloseable {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final String base, authorization;
  private volatile InputStream events;
  private volatile WebSocket socket;
  private volatile boolean closed;
  private volatile long latencyMs, lastSuccess;
  private final java.util.concurrent.atomic.AtomicInteger failures =
      new java.util.concurrent.atomic.AtomicInteger();

  public String diagnostics() {
    return "response "
        + latencyMs
        + " ms | last OK "
        + (lastSuccess == 0
            ? "never"
            : ((System.nanoTime() - lastSuccess) / 1_000_000_000L) + "s ago")
        + " | failed requests "
        + failures.get();
  }

  public ApiClient(String base, String user, String password) {
    URI uri = URI.create(base.trim());
    if (!java.util.Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null)
      throw new IllegalArgumentException(
          "Enter the Wi-Fi backend address, for example http://<laptop-ip>:8080");
    this.base = base.trim().replaceAll("/+$", "");
    authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  public String baseUrl() {
    return base;
  }

  public JsonNode request(String method, String path, Object body) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(base + "/api" + path))
            .timeout(Duration.ofSeconds(6))
            .header("Authorization", authorization);
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (body != null) b.header("Content-Type", "application/json");
    long started = System.nanoTime();
    HttpResponse<String> r;
    try {
      r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      failures.incrementAndGet();
      throw e;
    }
    latencyMs = (System.nanoTime() - started) / 1_000_000L;
    if (r.statusCode() / 100 == 2) {
      lastSuccess = System.nanoTime();
      failures.set(0);
    } else failures.incrementAndGet();
    if (r.statusCode() / 100 != 2)
      throw new IOException(
          "HTTP "
              + r.statusCode()
              + ": "
              + r.body().substring(0, Math.min(220, r.body().length())));
    return json.readTree(r.body());
  }

  public void listen(Runnable update) throws Exception {
    var finished = new java.util.concurrent.CountDownLatch(1);
    WebSocket connectedSocket =
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .header("Authorization", authorization)
            .buildAsync(
                URI.create(base.replaceFirst("^http", "ws") + "/api/ws"),
                new WebSocket.Listener() {
                  private final StringBuilder message = new StringBuilder();

                  @Override
                  public void onOpen(WebSocket ws) {
                    ws.request(1);
                  }

                  @Override
                  public java.util.concurrent.CompletionStage<?> onText(
                      WebSocket ws, CharSequence data, boolean last) {
                    message.append(data);
                    if (last) {
                      if (message.toString().contains("\"update\"") && !closed) update.run();
                      message.setLength(0);
                    }
                    ws.request(1);
                    return null;
                  }

                  @Override
                  public java.util.concurrent.CompletionStage<?> onClose(
                      WebSocket ws, int code, String reason) {
                    finished.countDown();
                    return null;
                  }

                  @Override
                  public void onError(WebSocket ws, Throwable error) {
                    finished.countDown();
                  }
                })
            .get(8, java.util.concurrent.TimeUnit.SECONDS);
    socket = connectedSocket;
    try {
      finished.await(60, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      connectedSocket.abort();
    }
  }

  public void pause() {
    closed = true;
    if (socket != null) socket.abort();
    try {
      if (events != null) events.close();
    } catch (IOException ignored) {
    }
  }

  public void resume() {
    closed = false;
  }

  @Override
  public void close() {
    pause();
    http.shutdownNow();
  }
}
