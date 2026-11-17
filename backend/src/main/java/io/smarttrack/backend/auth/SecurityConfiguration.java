package io.smarttrack.backend.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http, AuthService auth) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/v1/auth/register",
                        "/v1/auth/login",
                        "/actuator/health",
                        "/v1/info",
                        "/contracts/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (r, s, x) -> {
                      s.setStatus(401);
                      s.setContentType("application/problem+json");
                      s.getWriter().write("{\"title\":\"Authentication required\",\"status\":401}");
                    }))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest r, HttpServletResponse s, FilterChain c)
                  throws ServletException, IOException {
                String h = r.getHeader("Authorization");
                Identity id =
                    h != null && h.startsWith("Bearer ") ? auth.authenticate(h.substring(7)) : null;
                if (id != null)
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          new UsernamePasswordAuthenticationToken(id, null, List.of()));
                c.doFilter(r, s);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
