package io.smarttrack.desktop;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smarttrack.contract.BackendEndpoint;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class ApiClient implements AutoCloseable {
  public final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private volatile String base, token;
  private volatile WebSocket socket;
  private final java.util.concurrent.atomic.AtomicInteger generation =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile long lastStreamAttempt;

  public ApiClient(String base) {
    setBase(base);
  }

  public void setBase(String value) {
    base = BackendEndpoint.normalize(value);
  }

  public String base() {
    return base;
  }

  public void token(String token) {
    this.token = token;
  }

  public String token() {
    return token;
  }

  public CompletableFuture<JsonNode> request(String method, String path, Object body) {
    String requestBase = base, requestToken = token;
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            var b =
                HttpRequest.newBuilder(URI.create(requestBase + path))
                    .timeout(Duration.ofSeconds(10));
            if (requestToken != null) b.header("Authorization", "Bearer " + requestToken);
            if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
            else
              b.header("Content-Type", "application/json")
                  .method(
                      method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result;
            try {
              result = json.readTree(r.body());
            } catch (Exception invalid) {
              throw new IllegalStateException(
                  "This address did not return the SmartTrack API. Check the address and port"
                      + " (normally 8087).");
            }
            if (r.statusCode() >= 400)
              throw new IllegalStateException(
                  (result == null ? json.createObjectNode() : result)
                      .path("detail")
                      .asText(
                          result.path("title").asText("Request failed (" + r.statusCode() + ")")));
            if (result == null)
              throw new IllegalStateException("The backend returned an empty response");
            return result;
          } catch (Exception e) {
            throw new CompletionException(
                new IllegalStateException(BackendEndpoint.failure(e, requestBase)));
          }
        },
        workers);
  }

  public void stream(String device, Consumer<JsonNode> receive, Consumer<String> status) {
    disconnectStream();
    int currentGeneration = generation.get();
    lastStreamAttempt = System.nanoTime();
    String url = base.replaceFirst("^http", "ws") + "/v1/stream?deviceId=" + device;
    http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + token)
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(
            URI.create(url),
            new WebSocket.Listener() {
              StringBuilder text = new StringBuilder();

              public void onOpen(WebSocket ws) {
                if (currentGeneration != generation.get()) {
                  ws.abort();
                  return;
                }
                socket = ws;
                status.accept("Subscribing to live stream");
                ws.request(1);
              }

              public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                if (currentGeneration != generation.get()) return null;
                if (text.length() + data.length() > 65536) {
                  ws.abort();
                  return null;
                }
                text.append(data);
                if (last) {
                  try {
                    JsonNode frame = json.readTree(text.toString());
                    if (frame.path("type").asText().equals("ready"))
                      status.accept("Live stream connected");
                    else if (frame.has("telemetry")) receive.accept(frame);
                  } catch (Exception e) {
                    status.accept("Invalid stream data");
                  }
                  text.setLength(0);
                }
                ws.request(1);
                return null;
              }

              public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                if (currentGeneration != generation.get()) return null;
                socket = null;
                status.accept("Stream disconnected · polling");
                return null;
              }

              public void onError(WebSocket ws, Throwable e) {
                if (currentGeneration != generation.get()) return;
                socket = null;
                status.accept("Stream unavailable · polling");
              }
            })
        .exceptionally(
            e -> {
              if (currentGeneration == generation.get())
                status.accept("Stream unavailable · polling");
              return null;
            });
  }

  public void disconnectStream() {
    generation.incrementAndGet();
    if (socket != null) {
      socket.abort();
      socket = null;
    }
  }

  public boolean shouldReconnectStream() {
    return socket == null && System.nanoTime() - lastStreamAttempt > TimeUnit.SECONDS.toNanos(10);
  }

  public void close() {
    disconnectStream();
    workers.shutdownNow();
  }
}
