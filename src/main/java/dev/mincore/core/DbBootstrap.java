/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort DB bootstrap: create the schema/database if it's missing. */
final class DbBootstrap {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private DbBootstrap() {}

  /**
   * @return true if the vendor error code signals "Unknown database" (1049 for MariaDB/MySQL).
   */
  static boolean isUnknownDatabase(SQLException e) {
    if (e == null) return false;
    if (e.getErrorCode() == 1049) return true;
    String m = String.valueOf(e.getMessage()).toLowerCase();
    return m.contains("unknown database");
  }

  /**
   * Attempt to create the database if it doesn't exist.
   *
   * @param jdbcUrl like jdbc:mariadb://host:port/dbname?opts
   * @param user db user
   * @param pass db password
   */
  static void ensureDatabaseExists(String jdbcUrl, String user, String pass) throws SQLException {
    Parsed p = Parsed.from(jdbcUrl);
    String rootUrl = "jdbc:mariadb://" + p.hostPort + "/"; // connect without a database
    LOG.warn("(mincore) database '{}' missing; attempting to create via {}", p.db, rootUrl);
    try (Connection c = DriverManager.getConnection(rootUrl, user, pass);
        var st = c.createStatement()) {
      st.executeUpdate(
          "CREATE DATABASE IF NOT EXISTS `"
              + p.db
              + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
    LOG.info("(mincore) database '{}' created (or already existed)", p.db);
  }

  /** Minimal JDBC URL parser for jdbc:mariadb://host[:port]/db[?opt=...] */
  private static final class Parsed {
    final String hostPort;
    final String db;

    private Parsed(String hostPort, String db) {
      this.hostPort = hostPort;
      this.db = db;
    }

    static Parsed from(String url) {
      // strip prefix
      String s = url;
      if (s.startsWith("jdbc:mariadb://")) s = s.substring("jdbc:mariadb://".length());
      int slash = s.indexOf('/');
      if (slash < 0) throw new IllegalArgumentException("No / in JDBC url: " + url);
      String hostPort = s.substring(0, slash);
      String rest = s.substring(slash + 1);
      int q = rest.indexOf('?');
      String db = (q >= 0) ? rest.substring(0, q) : rest;
      if (db.isEmpty()) throw new IllegalArgumentException("No database name in JDBC url: " + url);
      return new Parsed(hostPort, db);
    }
  }
}
