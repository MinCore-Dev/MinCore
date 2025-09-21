/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.storage.ExtensionDatabase;
import dev.mincore.api.storage.SchemaHelper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** Default implementation of {@link ExtensionDatabase} backed by the shared Hikari pool. */
public final class ExtensionDbImpl implements ExtensionDatabase {
  private final DataSource ds;
  private final SchemaHelper schemaHelper;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   */
  public ExtensionDbImpl(DataSource ds) {
    this.ds = ds;
    this.schemaHelper = new SchemaHelperImpl(ds);
  }

  @Override
  public Connection borrowConnection() throws SQLException {
    return ds.getConnection();
  }

  @Override
  public boolean tryAdvisoryLock(String name) {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      var rs = s.executeQuery("SELECT GET_LOCK('" + name.replace("'", "''") + "', 0)");
      if (rs.next()) return rs.getInt(1) == 1;
    } catch (SQLException e) {
      return false;
    }
    return false;
  }

  @Override
  public void releaseAdvisoryLock(String name) {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.executeQuery("SELECT RELEASE_LOCK('" + name.replace("'", "''") + "')");
    } catch (SQLException ignored) {
    }
  }

  @Override
  public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
    SQLException last = null;
    for (int i = 0; i < 3; i++) {
      try {
        return action.get();
      } catch (SQLException e) {
        last = e;
        try {
          Thread.sleep(50L * (i + 1));
        } catch (InterruptedException ignored) {
        }
      }
    }
    throw last;
  }

  @Override
  public SchemaHelper schema() {
    return schemaHelper;
  }
}
