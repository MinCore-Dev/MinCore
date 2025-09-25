/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.storage.SchemaHelper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Basic implementation backed by information_schema. */
final class SchemaHelperImpl implements SchemaHelper {
  private static final Pattern TABLE_PATTERN =
      Pattern.compile(
          "(?i)create\\s+table\\s+(if\\s+not\\s+exists\\s+)?(?:(`)([^`]+)`|([a-z0-9_\\.]+))");
  private final DataSource ds;

  SchemaHelperImpl(DataSource ds) {
    this.ds = ds;
  }

  @Override
  public void ensureTable(String createSql) throws SQLException {
    ensureTable(extractTableName(createSql), createSql);
  }

  @Override
  public void ensureTable(String table, String createSql) throws SQLException {
    Objects.requireNonNull(createSql, "createSql");
    String candidate = table != null ? table : extractTableName(createSql);
    if (candidate != null && tableExists(candidate)) {
      return;
    }
    execute(createSql);
  }

  @Override
  public boolean tableExists(String table) throws SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = ?
      """;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  @Override
  public boolean hasColumn(String table, String column) throws SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name=? AND column_name=?
      """;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  @Override
  public void addColumnIfMissing(String table, String column, String columnDef)
      throws SQLException {
    if (hasColumn(table, column)) return;
    execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
  }

  @Override
  public boolean hasIndex(String table, String indexName) throws SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name=? AND index_name=?
      """;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, indexName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  @Override
  public void ensureIndex(String table, String indexName, String createIndexSql)
      throws SQLException {
    if (hasIndex(table, indexName)) {
      return;
    }
    execute(createIndexSql);
  }

  @Override
  public void ensureCheck(String table, String checkName, String addCheckSql) throws SQLException {
    if (hasCheck(table, checkName)) {
      return;
    }
    execute(addCheckSql);
  }

  @Override
  public boolean hasCheck(String table, String checkName) throws SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name=? AND constraint_name=? AND constraint_type='CHECK'
      """;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, checkName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  @Override
  public boolean hasPrimaryKey(String table, String constraintName) throws SQLException {
    return hasConstraint(table, constraintName, "PRIMARY KEY");
  }

  @Override
  public void ensurePrimaryKey(String table, String constraintName, String addPrimaryKeySql)
      throws SQLException {
    if (hasPrimaryKey(table, constraintName)) {
      return;
    }
    execute(addPrimaryKeySql);
  }

  @Override
  public boolean hasForeignKey(String table, String constraintName) throws SQLException {
    return hasConstraint(table, constraintName, "FOREIGN KEY");
  }

  @Override
  public void ensureForeignKey(String table, String constraintName, String addForeignKeySql)
      throws SQLException {
    if (hasForeignKey(table, constraintName)) {
      return;
    }
    execute(addForeignKeySql);
  }

  private boolean hasConstraint(String table, String constraintName, String type)
      throws SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name=? AND constraint_name=? AND constraint_type=?
      """;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, constraintName);
      ps.setString(3, type);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  private void execute(String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String extractTableName(String createSql) {
    if (createSql == null) {
      return null;
    }
    Matcher matcher = TABLE_PATTERN.matcher(createSql);
    if (!matcher.find()) {
      return null;
    }
    String backtickName = matcher.group(3);
    String plainName = matcher.group(4);
    String raw = backtickName != null ? backtickName : plainName;
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim();
    if (normalized.contains(".")) {
      normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }
}
