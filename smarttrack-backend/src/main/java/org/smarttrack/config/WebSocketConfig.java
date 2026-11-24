package org.smarttrack.config;

import org.smarttrack.repository.AppUserRepository;
import org.smarttrack.service.UpdateHub;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final UpdateHub hub;
  private final AppUserRepository users;

  public WebSocketConfig(UpdateHub h, AppUserRepository u) {
    hub = h;
    users = u;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(
        new TextWebSocketHandler() {
          @Override
          public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            var principal = session.getPrincipal();
            if (principal == null) {
              session.close(CloseStatus.POLICY_VIOLATION);
              return;
            }
            hub.addSocket(session, users.findByUsername(principal.getName()).orElseThrow().getId());
          }

          @Override
          public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            hub.removeSocket(s);
          }

          @Override
          public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
            hub.removeSocket(s);
            s.close(CloseStatus.SERVER_ERROR);
          }
        },
        "/api/ws");
  }
}
