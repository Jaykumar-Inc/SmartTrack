package org.smarttrack.jdbc;

import java.sql.*;

/**
 * Standalone read-only JDBC demonstration; production writes remain exclusively in JPA services.
 */
public final class JdbcReport {
  public static void main(String[] args) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
        PreparedStatement p =
            c.prepareStatement(
                "SELECT d.device_id, COUNT(r.id) AS samples FROM devices d LEFT JOIN"
                    + " sensor_readings r ON d.id=r.device_id WHERE d.owner_id=? GROUP BY"
                    + " d.device_id")) {
      p.setLong(1, Long.parseLong(args[0]));
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) System.out.println(r.getString("device_id") + "," + r.getLong("samples"));
      }
    }
  }
}
