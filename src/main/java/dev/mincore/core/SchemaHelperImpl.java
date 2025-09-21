/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.storage.SchemaHelper;
import java.sql.ResultSet;
import javax.sql.DataSource;

/** Basic implementation backed by information_schema. */
final class SchemaHelperImpl implements SchemaHelper {
  private final DataSource ds;

  SchemaHelperImpl(DataSource ds) {
    this.ds = ds;
  }

  @Override
  public void ensureTable(String createSql) throws java.sql.SQLException {
    try (var c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute(createSql);
    }
  }

  @Override
  public boolean hasColumn(String table, String column) throws java.sql.SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name=? AND column_name=?
      """;
    try (var c = ds.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  @Override
  public void addColumnIfMissing(String table, String column, String columnDef)
      throws java.sql.SQLException {
    if (hasColumn(table, column)) return;
    try (var c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
    }
  }

  @Override
  public void ensureIndex(String table, String indexName, String createIndexSql)
      throws java.sql.SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name=? AND index_name=?
      """;
    try (var c = ds.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, indexName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getInt(1) > 0) return;
      }
    }
    try (var c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute(createIndexSql);
    }
  }

  @Override
  public void ensureCheck(String table, String checkName, String addCheckSql)
      throws java.sql.SQLException {
    String sql =
        """
      SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name=? AND constraint_name=? AND constraint_type='CHECK'
      """;
    try (var c = ds.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, table);
      ps.setString(2, checkName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getInt(1) > 0) return;
      }
    }
    try (var c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute(addCheckSql);
    }
  }
}
