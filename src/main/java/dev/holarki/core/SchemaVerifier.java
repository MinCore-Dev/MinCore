/* Holarki © 2025 — MIT */
package dev.holarki.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates that the database schema version matches the runtime expectation. */
public final class SchemaVerifier {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private SchemaVerifier() {}

  /**
   * Ensures the schema version recorded in the database is compatible with this build.
   *
   * @param services live services container used to access the module database
   */
  public static void verify(Services services) {
    int expected = Migrations.currentVersion();
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT version FROM core_schema_version ORDER BY version DESC LIMIT 1")) {
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) {
        throw new IllegalStateException(
            "core_schema_version table is empty; migrations may not have been applied correctly");
      }
      int version = rs.getInt(1);
      if (version > expected) {
        throw new IllegalStateException(
            "Database schema version "
                + version
                + " is newer than supported runtime version "
                + expected);
      }
      if (version < expected) {
        throw new IllegalStateException(
            "Database schema version "
                + version
                + " is older than required runtime version "
                + expected
                + " (migrations should have applied)");
      }
      LOG.info("(holarki) schema version {} verified", version);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to verify schema version", e);
    }
  }
}
