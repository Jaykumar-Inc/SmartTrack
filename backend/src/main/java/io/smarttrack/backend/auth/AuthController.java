package io.smarttrack.backend.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  public record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 72) String password) {
    @Override
    public String toString() {
      return "Credentials[redacted]";
    }
  }

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/register")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, String> register(@Valid @RequestBody Credentials c) {
    auth.register(c.email(), c.password());
    return Map.of("status", "registered");
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody Credentials c) {
    return auth.login(c.email(), c.password());
  }

  @PostMapping("/logout")
  public Map<String, String> logout(@RequestHeader("Authorization") String h) {
    auth.logout(h.substring(7));
    return Map.of("status", "signed_out");
  }
}
