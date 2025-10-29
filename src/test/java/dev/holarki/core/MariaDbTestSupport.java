/* Holarki © 2025 — MIT */
package dev.holarki.core;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Shared embedded MariaDB lifecycle for integration tests. */
final class MariaDbTestSupport {
  static final String USER = "root";
  static final String PASSWORD = "";

  private static final DB EMBEDDED_DB;
  private static final int PORT;
  private static final String BASE_URL;

  static {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
      builder.setPort(0);
      builder.setDeletingTemporaryBaseAndDataDirsOnShutdown(true);
      builder.setSecurityDisabled(true);
      builder.addArg("--user=" + USER);
      EMBEDDED_DB = DB.newEmbeddedDB(builder.build());
      EMBEDDED_DB.start();
      PORT = EMBEDDED_DB.getConfiguration().getPort();
      BASE_URL = "jdbc:mariadb://127.0.0.1:" + PORT + "/";
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      EMBEDDED_DB.stop();
                    } catch (Exception ignored) {
                    }
                  }));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private MariaDbTestSupport() {}

  static void ensureDatabase(String name) throws SQLException {
    try (Connection c = DriverManager.getConnection(baseUrl("mysql"), USER, PASSWORD);
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "CREATE DATABASE IF NOT EXISTS "
              + name
              + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
  }

  static void dropDatabase(String name) throws SQLException {
    try (Connection c = DriverManager.getConnection(baseUrl("mysql"), USER, PASSWORD);
        Statement st = c.createStatement()) {
      st.executeUpdate("DROP DATABASE IF EXISTS " + name);
    }
  }

  static String jdbcUrl(String database) {
    return baseUrl(database) + "?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=UTC";
  }

  static int port() {
    return PORT;
  }

  private static String baseUrl(String schema) {
    return BASE_URL + schema;
  }
}
