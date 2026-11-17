package io.smarttrack.backend.telemetry;

import io.smarttrack.backend.auth.Identity;
import io.smarttrack.backend.devices.DeviceService;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.util.UriComponentsBuilder;

/** User identity comes from the authenticated HTTP upgrade; tokens never enter URLs. */
@Configuration
@EnableWebSocket
public class TelemetryStream extends TextWebSocketHandler implements WebSocketConfigurer {
  private record Subscriber(String owner, String device, WebSocketSession session) {}

  private final Map<String, Subscriber> sessions = new ConcurrentHashMap<>();
  private final DeviceService devices;
  private final TelemetryService telemetry;

  public TelemetryStream(DeviceService devices, TelemetryService telemetry) {
    this.devices = devices;
    this.telemetry = telemetry;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
    r.addHandler(this, "/v1/stream");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
    var identity = (Identity) ((Authentication) raw.getPrincipal()).getPrincipal();
    devices.userOnly(identity);
    String device =
        UriComponentsBuilder.fromUri(raw.getUri()).build().getQueryParams().getFirst("deviceId");
    try {
      devices.authorize(identity, device);
    } catch (Exception e) {
      raw.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    var safe = new ConcurrentWebSocketSessionDecorator(raw, 1000, 65536);
    sessions.put(raw.getId(), new Subscriber(identity.userId(), device, safe));
    safe.sendMessage(
        new TextMessage(telemetry.encode(Map.of("type", "ready", "deviceId", device))));
  }

  @TransactionalEventListener
  public void committed(TelemetryService.Committed e) {
    String json = telemetry.encode(e.reading());
    for (var s : sessions.values()) {
      if (s.owner.equals(e.ownerId()) && s.device.equals(e.deviceId()))
        try {
          s.session.sendMessage(new TextMessage(json));
        } catch (Exception x) {
          sessions.remove(s.session.getId());
          try {
            s.session.close(CloseStatus.SERVER_ERROR);
          } catch (IOException ignored) {
          }
        }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus c) {
    sessions.remove(s.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession s, Throwable t) throws Exception {
    sessions.remove(s.getId());
    s.close(CloseStatus.SERVER_ERROR);
  }
}
