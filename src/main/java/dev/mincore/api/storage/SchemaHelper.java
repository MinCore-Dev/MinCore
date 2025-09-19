/* MinCore © 2025 — MIT */
package dev.mincore.api.storage;

import java.sql.SQLException;

/**
 * Helper interface for add-ons to evolve their own database schema safely at startup.
 *
 * <p>All methods are intended to be <em>idempotent</em>, so they can be called on every server boot
 * without causing errors. A typical add-on will obtain an instance of this interface from {@code
 * MinCoreApi.database().schema()} and then:
 *
 * <ol>
 *   <li>Create tables with {@link #ensureTable(String)}.
 *   <li>Add missing columns with {@link #addColumnIfMissing(String, String, String)}.
 *   <li>Add indexes with {@link #ensureIndex(String, String, String)}.
 *   <li>Verify a named {@code CHECK} constraint with {@link #ensureCheck(String, String, String)}.
 * </ol>
 *
 * <p>Implementations are provided by MinCore; add-ons should not implement this interface.
 */
public interface SchemaHelper {

  /**
   * Ensures a table exists. The provided SQL must already be written to be idempotent, typically
   * using {@code CREATE TABLE IF NOT EXISTS ...}.
   *
   * @param createSql the full DDL statement to create the table if it does not exist
   * @throws SQLException if the statement fails to execute
   */
  void ensureTable(String createSql) throws SQLException;

  /**
   * Checks whether a table currently has a column.
   *
   * @param table the table name (unquoted identifier as used in DDL)
   * @param column the column name to check
   * @return {@code true} if the table contains the column; {@code false} otherwise
   * @throws SQLException if the metadata query fails
   */
  boolean hasColumn(String table, String column) throws SQLException;

  /**
   * Adds a column to a table if it is missing.
   *
   * <p>The {@code columnDef} should contain the full column definition as it would appear after
   * {@code ADD COLUMN}, for example:
   *
   * <pre>{@code
   * addColumnIfMissing("shop_orders", "note", "VARCHAR(128) NULL DEFAULT NULL");
   * }</pre>
   *
   * @param table the table name
   * @param column the column name to add
   * @param columnDef the SQL column definition (type and modifiers)
   * @throws SQLException if adding the column fails
   */
  void addColumnIfMissing(String table, String column, String columnDef) throws SQLException;

  /**
   * Ensures an index exists on a table. The create statement should be idempotent (for example,
   * using {@code CREATE INDEX IF NOT EXISTS} where supported) or otherwise safe to re-run.
   *
   * @param table the table name
   * @param indexName a unique index name used by the implementation to check existence
   * @param createIndexSql a complete SQL statement that creates the index if it is missing
   * @throws SQLException if creating the index fails
   */
  void ensureIndex(String table, String indexName, String createIndexSql) throws SQLException;

  /**
   * Ensures a named {@code CHECK} constraint exists on a table; if it is missing, executes the
   * provided SQL to add it.
   *
   * <p>The {@code addCheckSql} statement should be a complete, valid DDL statement that introduces
   * the check constraint with the supplied name (for example, using {@code ALTER TABLE ... ADD
   * CONSTRAINT chk_xyz CHECK (...)}). The implementation will avoid re-adding a duplicate by
   * checking for {@code checkName} first.
   *
   * @param table the table name
   * @param checkName the exact constraint name to ensure is present
   * @param addCheckSql the SQL to add the constraint if missing
   * @throws SQLException if adding the constraint fails
   */
  void ensureCheck(String table, String checkName, String addCheckSql) throws SQLException;
}
