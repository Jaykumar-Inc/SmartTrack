package org.smarttrack.jdbc;

import java.io.Console;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Development provisioning helper. Only a BCrypt hash, never the password, is printed. */
public final class SeedPasswordHash {
  public static void main(String[] args) {
    Console console = System.console();
    if (console == null)
      throw new IllegalStateException("Run from an interactive terminal for hidden password entry");
    char[] password = console.readPassword("Development user password (12+ characters): ");
    try {
      if (password == null || password.length < 12)
        throw new IllegalArgumentException("Use at least 12 characters");
      console.printf("%s%n", new BCryptPasswordEncoder().encode(new String(password)));
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }
  }
}
