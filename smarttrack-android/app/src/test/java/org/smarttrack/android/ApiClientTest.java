package org.smarttrack.android;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;

public class ApiClientTest {
  static final class Fixture implements AutoCloseable {
    final ServerSocket server;
    final Thread thread;

    Fixture(int status, String body, AtomicReference<String> authorization) throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      thread =
          new Thread(
              () -> {
                try (Socket socket = server.accept()) {
                  BufferedReader reader =
                      new BufferedReader(
                          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                  String line;
                  while ((line = reader.readLine()) != null && !line.isEmpty())
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:"))
                      authorization.set(line.substring(line.indexOf(':') + 1).trim());
                  byte[] data = body.getBytes(StandardCharsets.UTF_8);
                  OutputStream out = socket.getOutputStream();
                  out.write(
                      ("HTTP/1.1 "
                              + status
                              + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                              + data.length
                              + "\r\nConnection: close\r\n\r\n")
                          .getBytes(StandardCharsets.US_ASCII));
                  out.write(data);
                  out.flush();
                } catch (IOException ignored) {
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return server.getLocalPort();
    }

    public void close() throws Exception {
      server.close();
      thread.join(1000);
    }
  }

  @Test
  public void readsSharedServerWithBasicAuthentication() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    Fixture server = new Fixture(200, "{\"username\":\"fixture-account\"}", authorization);
    ApiClient client =
        new ApiClient("http://127.0.0.1:" + server.port(), "fixture-account", "fixture-password");
    try {
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<Object> result = new AtomicReference<>();
      AtomicReference<Exception> failure = new AtomicReference<>();
      client.request(
          "GET",
          "/auth/me",
          null,
          (value, error) -> {
            result.set(value);
            failure.set(error);
            done.countDown();
          });
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertNull(failure.get());
      assertEquals("fixture-account", ((JSONObject) result.get()).getString("username"));
      assertEquals(
          "Basic "
              + java.util.Base64.getEncoder()
                  .encodeToString(
                      "fixture-account:fixture-password".getBytes(StandardCharsets.UTF_8)),
          authorization.get());
      assertTrue(client.isConnected());
    } finally {
      client.close();
      server.close();
    }
  }

  @Test
  public void reportsInvalidLoginWithoutTreatingItAsLive() throws Exception {
    Fixture server = new Fixture(401, "", new AtomicReference<>());
    ApiClient client = new ApiClient("127.0.0.1:" + server.port(), "fixture", "wrong");
    try {
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<Exception> error = new AtomicReference<>();
      client.request(
          "GET",
          "/auth/me",
          null,
          (v, e) -> {
            error.set(e);
            done.countDown();
          });
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertEquals("Incorrect username or password", error.get().getMessage());
      assertFalse(client.isConnected());
    } finally {
      client.close();
      server.close();
    }
  }

  @Test
  public void rejectsCredentialUrlsAndExtraApiPaths() {
    for (String address :
        new String[] {"ftp://example.test", "http://u:p@example.test", "http://example.test/api"}) {
      try {
        new ApiClient(address, "u", "p");
        fail("Invalid server address was accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
