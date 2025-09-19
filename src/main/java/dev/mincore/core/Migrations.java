/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent schema migrations for MinCore.
 *
 * <p>This class creates all core tables if they do not already exist:
 *
 * <ul>
 *   <li>{@code players} — player directory + wallet balance
 *   <li>{@code player_event_seq} — per-player monotonic sequence (for event ordering)
 *   <li>{@code player_events} — append-only player balance deltas (auditable history)
 *   <li>{@code idempotency} — exact-once guard for idempotent operations
 *   <li>{@code player_attributes} — per-player JSON attributes (key/value)
 *   <li>{@code core_ledger} — unified ledger (core + add-ons)
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <ul>
 *   <li>All statements are {@code CREATE TABLE IF NOT EXISTS} to avoid duplicate errors.
 *   <li>No {@code ALTER TABLE ADD CONSTRAINT} is issued on repeated boots (prevents duplicate-check
 *       errors).
 *   <li>Tables are InnoDB with utf8mb4 and {@code ROW_FORMAT=DYNAMIC} for JSON-ish payloads.
 * </ul>
 */
public final class Migrations {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private Migrations() {}

  /**
   * Applies idempotent DDL. Any single-statement failure is logged and the migrator proceeds; a
   * hard connection failure will throw.
   *
   * @param services running services (for DB connection)
   */
  public static void apply(Services services) {
    final String[] DDL =
        new String[] {
          // --- players: directory + wallet
          """
        CREATE TABLE IF NOT EXISTS players (
          uuid            BINARY(16)      NOT NULL,
          name            VARCHAR(32)     NOT NULL,
          balance_units   BIGINT UNSIGNED NOT NULL DEFAULT 0,
          created_at_s    BIGINT UNSIGNED NOT NULL,
          updated_at_s    BIGINT UNSIGNED NOT NULL,
          seen_at_s       BIGINT UNSIGNED NULL,
          CONSTRAINT chk_balance_nonneg CHECK (balance_units >= 0),
          PRIMARY KEY (uuid),
          KEY idx_name (name),
          KEY idx_seen (seen_at_s)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """,

          // --- per-player sequence; used with LAST_INSERT_ID trick in wallet ops
          """
        CREATE TABLE IF NOT EXISTS player_event_seq (
          uuid BINARY(16) NOT NULL,
          seq  BIGINT UNSIGNED NOT NULL,
          PRIMARY KEY (uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """,

          // --- append-only balance deltas for auditability
          """
        CREATE TABLE IF NOT EXISTS player_events (
          uuid          BINARY(16)      NOT NULL,
          seq           BIGINT UNSIGNED NOT NULL,
          version       INT             NOT NULL,
          delta_units   BIGINT          NOT NULL,
          reason        VARCHAR(64)     NOT NULL,
          created_at_s  BIGINT UNSIGNED NOT NULL,
          PRIMARY KEY (uuid, seq),
          KEY idx_created (created_at_s)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """,

          // --- idempotency: (scope, key_hash) exact-once guard
          """
        CREATE TABLE IF NOT EXISTS idempotency (
          scope         VARCHAR(64)     NOT NULL,
          key_hash      BINARY(32)      NOT NULL,
          payload_hash  BINARY(32)      NULL,
          applied_at_s  BIGINT UNSIGNED NOT NULL,
          result_code   VARCHAR(32)     NULL,
          PRIMARY KEY (scope, key_hash),
          KEY idx_applied (applied_at_s)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """,

          // --- per-player JSON attributes (extensibility surface)
          """
        CREATE TABLE IF NOT EXISTS player_attributes (
          owner_uuid    BINARY(16)      NOT NULL,
          attr_key      VARCHAR(64)     NOT NULL,
          value_json    MEDIUMTEXT      NOT NULL,
          created_at_s  BIGINT UNSIGNED NOT NULL,
          updated_at_s  BIGINT UNSIGNED NOT NULL,
          PRIMARY KEY (owner_uuid, attr_key),
          KEY idx_updated (updated_at_s)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """,

          // --- unified ledger (core + add-ons)
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
          old_units       BIGINT UNSIGNED NULL,
          new_units       BIGINT UNSIGNED NULL,
          server_node     VARCHAR(64)     NULL,
          extra_json      MEDIUMTEXT      NULL,
          PRIMARY KEY (id),
          KEY idx_ts           (ts_s),
          KEY idx_addon        (addon_id),
          KEY idx_op           (op),
          KEY idx_from         (from_uuid),
          KEY idx_to           (to_uuid),
          KEY idx_reason       (reason),
          KEY idx_seq          (seq),
          KEY idx_idem_scope   (idem_scope)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
        """
        };

    try (Connection c = services.database().borrowConnection();
        Statement st = c.createStatement()) {
      for (String sql : DDL) {
        try {
          st.execute(sql);
        } catch (SQLException e) {
          // Non-fatal: log and continue with next statement.
          LOG.warn(
              "(mincore) migration statement failed; continuing. cause={} sql=\n{}",
              e.getMessage(),
              sql);
        }
      }
    } catch (SQLException e) {
      // Hard failure to borrow/execute — bubble up to fail fast at boot.
      throw new RuntimeException("Migration failed", e);
    }
  }
}
