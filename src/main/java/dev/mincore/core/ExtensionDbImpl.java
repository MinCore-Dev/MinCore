/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.storage.ExtensionDatabase;
import dev.mincore.api.storage.SchemaHelper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of {@link ExtensionDatabase} backed by the shared Hikari pool. */
public final class ExtensionDbImpl implements ExtensionDatabase, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private final DataSource ds;
  private final SchemaHelper schemaHelper;
  private final DbHealth dbHealth;
  private final Set<String> heldLocks = ConcurrentHashMap.newKeySet();

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   */
  public ExtensionDbImpl(DataSource ds, DbHealth dbHealth) {
    this.ds = ds;
    this.schemaHelper = new SchemaHelperImpl(ds);
    this.dbHealth = dbHealth;
  }

  @Override
  public Connection borrowConnection() throws SQLException {
    if (!dbHealth.allowWrite("extDb.borrowConnection")) {
      throw new SQLException("database is in degraded mode", "08000");
    }
    return ds.getConnection();
  }

  @Override
  public boolean tryAdvisoryLock(String name) {
    if (!dbHealth.allowWrite("extDb.tryAdvisoryLock")) {
      return false;
    }
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      var rs = s.executeQuery("SELECT GET_LOCK('" + name.replace("'", "''") + "', 0)");
      if (rs.next() && rs.getInt(1) == 1) {
        heldLocks.add(name);
        return true;
      }
    } catch (SQLException e) {
      dbHealth.markFailure(e);
      return false;
    }
    dbHealth.markSuccess();
    return false;
  }

  @Override
  public void releaseAdvisoryLock(String name) {
    if (!dbHealth.allowWrite("extDb.releaseAdvisoryLock")) {
      return;
    }
    releaseInternal(name, true);
  }

  @Override
  public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
    if (!dbHealth.allowWrite("extDb.withRetry")) {
      throw new SQLException("database is in degraded mode", "08000");
    }
    SQLException last = null;
    for (int i = 0; i < 3; i++) {
      try {
        T result = action.get();
        dbHealth.markSuccess();
        return result;
      } catch (SQLException e) {
        last = e;
        dbHealth.markFailure(e);
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

  @Override
  public void close() {
    List<String> snapshot = new ArrayList<>(heldLocks);
    for (String lock : snapshot) {
      releaseInternal(lock, false);
    }
  }

  private void releaseInternal(String name, boolean checkHealth) {
    try {
      if (checkHealth && !dbHealth.allowWrite("extDb.releaseAdvisoryLock")) {
        return;
      }
      try (Connection c = ds.getConnection();
          Statement s = c.createStatement()) {
        s.executeQuery("SELECT RELEASE_LOCK('" + name.replace("'", "''") + "')");
        dbHealth.markSuccess();
      }
    } catch (SQLException e) {
      dbHealth.markFailure(e);
      LOG.debug("(mincore) failed to release advisory lock {}: {}", name, e.getMessage());
    } finally {
      heldLocks.remove(name);
    }
  }
}
