package org.smarttrack.service;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.*;

/** Collections and thread safety: only the owner's authenticated streams receive notifications. */
@Service
public class UpdateHub {
  private final ConcurrentHashMap<SseEmitter, Long> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<WebSocketSession, Long> sockets = new ConcurrentHashMap<>();

  public void addSocket(WebSocketSession session, Long owner) {
    sockets.put(session, owner);
    sendSocket(session, "ready");
  }

  public void removeSocket(WebSocketSession session) {
    sockets.remove(session);
  }

  private void sendSocket(WebSocketSession session, String type) {
    try {
      synchronized (session) {
        if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"" + type + "\"}"));
        else sockets.remove(session);
      }
    } catch (Exception e) {
      sockets.remove(session);
      try {
        session.close();
      } catch (Exception ignored) {
      }
    }
  }

  public SseEmitter subscribe(Long owner) {
    var e = new SseEmitter(60_000L);
    clients.put(e, owner);
    e.onCompletion(() -> clients.remove(e));
    e.onTimeout(
        () -> {
          clients.remove(e);
          e.complete();
        });
    e.onError(x -> clients.remove(e));
    send(e, "ready", "connected");
    return e;
  }

  private void send(SseEmitter e, String name, Object data) {
    try {
      e.send(SseEmitter.event().name(name).data(data));
    } catch (Exception ex) {
      clients.remove(e);
      e.complete();
    }
  }

  public void publish(Long owner) {
    sockets.forEach(
        (session, id) -> {
          if (id.equals(owner)) sendSocket(session, "update");
        });
    clients.forEach(
        (e, id) -> {
          if (id.equals(owner)) send(e, "update", "refresh");
        });
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    sockets.keySet().forEach(s -> sendSocket(s, "heartbeat"));
    clients.keySet().forEach(e -> send(e, "heartbeat", "alive"));
  }
}
