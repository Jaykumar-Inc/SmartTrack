package io.smarttrack.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ApiClientTest {
  @Test
  void distinguishesWrongServiceFromBackendValidation() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/wrong",
        exchange -> {
          byte[] bytes = "<html>Another service</html>".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(404, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/auth",
        exchange -> {
          byte[] bytes =
              "{\"detail\":\"Invalid email or password\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(401, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (ApiClient api = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort())) {
      var wrong =
          assertThrows(CompletionException.class, () -> api.request("GET", "/wrong", null).join());
      assertTrue(wrong.getCause().getMessage().contains("8087"));
      var invalid =
          assertThrows(CompletionException.class, () -> api.request("GET", "/auth", null).join());
      assertEquals("Invalid email or password", invalid.getCause().getMessage());
    } finally {
      server.stop(0);
    }
  }
}
