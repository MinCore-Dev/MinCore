/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api.storage;

import java.sql.SQLException;

/**
 * Helper interface for bundled modules and operator automation run by server operators to evolve
 * their own database schema safely at startup.
 *
 * <p>All methods are intended to be <em>idempotent</em>, so they can be called on every server boot
 * without causing errors. A typical module or automation workflow will obtain an instance of this
 * interface from {@code HolarkiApi.database().schema()} and then:
 *
 * <ol>
 *   <li>Create tables with {@link #ensureTable(String)} or {@link #ensureTable(String, String)}.
 *   <li>Add missing columns with {@link #addColumnIfMissing(String, String, String)}.
 *   <li>Add indexes with {@link #ensureIndex(String, String, String)} (or {@link #hasIndex(String,
 *       String)} to test beforehand).
 *   <li>Verify a named {@code CHECK} constraint with {@link #ensureCheck(String, String, String)}
 *       (or {@link #hasCheck(String, String)} to test beforehand).
 *   <li>Guard foreign keys and primary keys with {@link #ensureForeignKey(String, String, String)}
 *       and {@link #ensurePrimaryKey(String, String, String)} as needed.
 * </ol>
 *
 * <p>Implementations are provided by Holarki; bundled modules and operator-maintained automation
 * should not implement this interface.
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
   * Ensures a table exists by name, optionally using {@code createSql} when the table is missing.
   *
   * @param table the table identifier as used in {@code information_schema}
   * @param createSql the full DDL statement to create the table when it is missing
   * @throws SQLException if checking metadata or executing the statement fails
   */
  void ensureTable(String table, String createSql) throws SQLException;

  /**
   * Checks whether a table currently exists in the active schema.
   *
   * @param table the table name (unquoted identifier as used in DDL)
   * @return {@code true} if the table exists in the current schema
   * @throws SQLException if the metadata query fails
   */
  boolean tableExists(String table) throws SQLException;

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
   * Checks whether the named index is already present on the table.
   *
   * @param table the table name
   * @param indexName the index identifier (case-sensitive match)
   * @return {@code true} if the index exists
   * @throws SQLException if metadata lookup fails
   */
  boolean hasIndex(String table, String indexName) throws SQLException;

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
   * Checks whether the named {@code CHECK} constraint exists on the table.
   *
   * @param table the table name
   * @param checkName the constraint name
   * @return {@code true} if the constraint exists
   * @throws SQLException if metadata lookup fails
   */
  boolean hasCheck(String table, String checkName) throws SQLException;

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

  /**
   * Checks whether the named {@code PRIMARY KEY} constraint exists on the table.
   *
   * @param table the table name
   * @param constraintName the constraint identifier
   * @return {@code true} if the primary key already exists
   * @throws SQLException if metadata lookup fails
   */
  boolean hasPrimaryKey(String table, String constraintName) throws SQLException;

  /**
   * Ensures a {@code PRIMARY KEY} exists for the table.
   *
   * @param table the table name
   * @param constraintName the constraint identifier to check for
   * @param addPrimaryKeySql DDL statement that adds the primary key if missing
   * @throws SQLException if metadata lookup or executing the statement fails
   */
  void ensurePrimaryKey(String table, String constraintName, String addPrimaryKeySql)
      throws SQLException;

  /**
   * Checks whether the named {@code FOREIGN KEY} constraint exists on the table.
   *
   * @param table the table name
   * @param constraintName the constraint identifier
   * @return {@code true} if the foreign key already exists
   * @throws SQLException if metadata lookup fails
   */
  boolean hasForeignKey(String table, String constraintName) throws SQLException;

  /**
   * Ensures a {@code FOREIGN KEY} constraint exists for the table.
   *
   * @param table the table name
   * @param constraintName the constraint identifier to check for
   * @param addForeignKeySql DDL statement that adds the foreign key if missing
   * @throws SQLException if metadata lookup or executing the statement fails
   */
  void ensureForeignKey(String table, String constraintName, String addForeignKeySql)
      throws SQLException;
}
