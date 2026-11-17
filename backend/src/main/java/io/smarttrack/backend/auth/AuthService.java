package io.smarttrack.backend.auth;

import static org.springframework.http.HttpStatus.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String dummyHash = passwords.encode("constant-time-invalid-user");

  public AuthService(JdbcTemplate db) {
    this.db = db;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String secret(String prefix) {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  @Transactional
  public void register(String email, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes");
    try {
      db.update(
          "INSERT INTO app_user(id,email,password_hash,created_at) VALUES(?,?,?,?)",
          UUID.randomUUID().toString(),
          email.toLowerCase(Locale.ROOT),
          passwords.encode(password),
          Timestamp.from(Instant.now()));
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      throw new ResponseStatusException(
          CONFLICT, "An account already exists for this email. Sign in instead.");
    }
  }

  public Map<String, Object> login(String email, String password) {
    var rows =
        db.queryForList(
            "SELECT id,password_hash FROM app_user WHERE email=?", email.toLowerCase(Locale.ROOT));
    String hash = rows.isEmpty() ? dummyHash : (String) rows.getFirst().get("password_hash");
    if (!passwords.matches(password, hash) || rows.isEmpty())
      throw new ResponseStatusException(UNAUTHORIZED, "Invalid email or password");
    String token = secret("usr_");
    Instant expiry = Instant.now().plus(Duration.ofHours(12));
    db.update(
        "INSERT INTO access_token(token_hash,user_id,expires_at) VALUES(?,?,?)",
        hash(token),
        rows.getFirst().get("id"),
        Timestamp.from(expiry));
    return Map.of("token", token, "expiresAt", expiry);
  }

  public Identity authenticate(String token) {
    if (token == null || token.length() > 128) return null;
    if (token.startsWith("usr_"))
      return db
          .query(
              "SELECT user_id FROM access_token WHERE token_hash=? AND expires_at>?",
              (rs, n) -> new Identity(rs.getString(1), null),
              hash(token),
              Timestamp.from(Instant.now()))
          .stream()
          .findFirst()
          .orElse(null);
    if (token.startsWith("dev_"))
      return db
          .query(
              "SELECT owner_id,id FROM device WHERE secret_hash=?",
              (rs, n) -> new Identity(rs.getString(1), rs.getString(2)),
              hash(token))
          .stream()
          .findFirst()
          .orElse(null);
    return null;
  }

  public void logout(String token) {
    db.update("DELETE FROM access_token WHERE token_hash=?", hash(token));
  }
}
