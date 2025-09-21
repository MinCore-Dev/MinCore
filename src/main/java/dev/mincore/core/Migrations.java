/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Idempotent schema migrations for MinCore core tables. */
public final class Migrations {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final int CURRENT_VERSION = 1;

  private Migrations() {}

  /**
   * Applies idempotent DDL. Each statement is executed independently; failures are logged and the
   * migrator proceeds with remaining statements.
   *
   * @param services service container supplying the database connection
   */
  public static void apply(Services services) {
    final String[] ddl = {
      // Schema version bookkeeping
      """
      CREATE TABLE IF NOT EXISTS core_schema_version (
        version       INT              NOT NULL,
        applied_at_s  BIGINT UNSIGNED  NOT NULL,
        PRIMARY KEY (version)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """,

      // Player directory + wallet balance
      """
      CREATE TABLE IF NOT EXISTS players (
        uuid            BINARY(16)      NOT NULL,
        name            VARCHAR(32)     NOT NULL,
        name_lower      VARCHAR(32) GENERATED ALWAYS AS (LOWER(name)) STORED,
        balance_units   BIGINT          NOT NULL DEFAULT 0,
        created_at_s    BIGINT UNSIGNED NOT NULL,
        updated_at_s    BIGINT UNSIGNED NOT NULL,
        seen_at_s       BIGINT UNSIGNED NULL,
        CONSTRAINT chk_players_balance_nonneg CHECK (balance_units >= 0),
        PRIMARY KEY (uuid),
        KEY idx_players_name_lower (name_lower),
        KEY idx_players_seen (seen_at_s)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """,

      // Per-player monotonic sequence (wallet + player events)
      """
      CREATE TABLE IF NOT EXISTS player_event_seq (
        uuid BINARY(16) NOT NULL,
        seq  BIGINT UNSIGNED NOT NULL,
        PRIMARY KEY (uuid)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """,

      // Idempotency guard
      """
      CREATE TABLE IF NOT EXISTS core_requests (
        scope         VARCHAR(64)     NOT NULL,
        key_hash      BINARY(32)      NOT NULL,
        payload_hash  BINARY(32)      NOT NULL,
        ok            TINYINT(1)      NOT NULL DEFAULT 0,
        created_at_s  BIGINT UNSIGNED NOT NULL,
        expires_at_s  BIGINT UNSIGNED NOT NULL,
        PRIMARY KEY (scope, key_hash),
        KEY idx_core_requests_expires (expires_at_s)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """,

      // Per-player JSON attributes
      """
      CREATE TABLE IF NOT EXISTS player_attributes (
        owner_uuid    BINARY(16)      NOT NULL,
        attr_key      VARCHAR(64)     NOT NULL,
        value_json    MEDIUMTEXT      NOT NULL,
        created_at_s  BIGINT UNSIGNED NOT NULL,
        updated_at_s  BIGINT UNSIGNED NOT NULL,
        CONSTRAINT chk_player_attr_json CHECK (JSON_VALID(value_json)),
        CONSTRAINT chk_player_attr_size CHECK (CHAR_LENGTH(value_json) <= 8192),
        PRIMARY KEY (owner_uuid, attr_key),
        KEY idx_player_attr_updated (updated_at_s)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """,

      // Unified ledger
      """
      CREATE TABLE IF NOT EXISTS core_ledger (
        id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        ts_s            BIGINT UNSIGNED NOT NULL,
        addon_id        VARCHAR(64)     NOT NULL,
        op              VARCHAR(32)     NOT NULL,
        from_uuid       BINARY(16)      NULL,
        to_uuid         BINARY(16)      NULL,
        amount          BIGINT          NOT NULL,
        reason          VARCHAR(64)     NOT NULL,
        ok              TINYINT(1)      NOT NULL,
        code            VARCHAR(32)     NULL,
        seq             BIGINT UNSIGNED NOT NULL DEFAULT 0,
        idem_scope      VARCHAR(64)     NULL,
        idem_key_hash   BINARY(32)      NULL,
        old_units       BIGINT          NULL,
        new_units       BIGINT          NULL,
        server_node     VARCHAR(64)     NULL,
        extra_json      MEDIUMTEXT      NULL,
        PRIMARY KEY (id),
        KEY idx_core_ledger_ts (ts_s),
        KEY idx_core_ledger_addon (addon_id),
        KEY idx_core_ledger_op (op),
        KEY idx_core_ledger_from (from_uuid),
        KEY idx_core_ledger_to (to_uuid),
        KEY idx_core_ledger_reason (reason),
        KEY idx_core_ledger_seq (seq),
        KEY idx_core_ledger_idem_scope (idem_scope)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
      """
    };

    boolean allSucceeded = true;
    try (Connection c = services.database().borrowConnection();
        Statement st = c.createStatement()) {
      for (String sql : ddl) {
        try {
          st.execute(sql);
        } catch (SQLException e) {
          allSucceeded = false;
          LOG.warn(
              "(mincore) migration statement failed; continuing. cause={} sql=\n{}",
              e.getMessage(),
              sql);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Migration failed", e);
    }

    if (!allSucceeded) {
      LOG.warn("(mincore) migrations completed with errors; schema version unchanged");
      return;
    }

    recordSchemaVersion(services);
  }

  /** Current schema version number. */
  public static int currentVersion() {
    return CURRENT_VERSION;
  }

  private static void recordSchemaVersion(Services services) {
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO core_schema_version(version, applied_at_s) VALUES(?, ?) "
                    + "ON DUPLICATE KEY UPDATE applied_at_s=VALUES(applied_at_s)")) {
      c.setAutoCommit(false);
      ps.setInt(1, CURRENT_VERSION);
      ps.setLong(2, Instant.now().getEpochSecond());
      ps.executeUpdate();
      c.commit();
      LOG.info("(mincore) schema version recorded: {}", CURRENT_VERSION);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to record schema version", e);
    }
  }
}
