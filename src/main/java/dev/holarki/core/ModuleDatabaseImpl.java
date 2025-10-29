/* Holarki © 2025 — MIT */
package dev.holarki.core;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of {@link ModuleDatabase} backed by the shared Hikari pool. */
public final class ModuleDatabaseImpl implements ModuleDatabase, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private final DataSource ds;
  private final SchemaHelper schemaHelper;
  private final DbHealth dbHealth;
  private final Metrics metrics;
  private final Set<String> heldLocks = ConcurrentHashMap.newKeySet();
  private static final Pattern LOCK_NAME_PATTERN = Pattern.compile("[A-Za-z0-9:_\\-\\.]{1,64}");

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   * @param dbHealth health monitor for degraded mode handling
   */
  public ModuleDatabaseImpl(DataSource ds, DbHealth dbHealth, Metrics metrics) {
    this.ds = ds;
    this.schemaHelper = new SchemaHelperImpl(ds);
    this.dbHealth = dbHealth;
    this.metrics = metrics;
  }

  @Override
  public Connection borrowConnection() throws SQLException {
    if (!dbHealth.allowWrite("moduleDb.borrowConnection")) {
      throw new SQLException("database is in degraded mode", "08000");
    }
    return ds.getConnection();
  }

  @Override
  public boolean tryAdvisoryLock(String name) {
    if (!dbHealth.allowWrite("moduleDb.tryAdvisoryLock")) {
      return false;
    }
    String lock = validateLockName(name);
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
      ps.setString(1, lock);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getInt(1) == 1) {
          heldLocks.add(lock);
          dbHealth.markSuccess();
          if (metrics != null) {
            metrics.recordModuleOperation(true, null);
          }
          return true;
        }
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      if (metrics != null) {
        metrics.recordModuleOperation(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "moduleDb.tryAdvisoryLock",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
      return false;
    }
    dbHealth.markSuccess();
    if (metrics != null) {
      metrics.recordModuleOperation(false, ErrorCode.DEGRADED_MODE);
    }
    return false;
  }

  @Override
  public void releaseAdvisoryLock(String name) {
    releaseInternal(validateLockName(name), true);
  }

  @Override
  public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
    if (!dbHealth.allowWrite("moduleDb.withRetry")) {
      throw new SQLException("database is in degraded mode", "08000");
    }
    SQLException last = null;
    for (int i = 0; i < 3; i++) {
      try {
        T result = action.get();
        dbHealth.markSuccess();
        if (metrics != null) {
          metrics.recordModuleOperation(true, null);
        }
        return result;
      } catch (SQLException e) {
        last = e;
        ErrorCode code = SqlErrorCodes.classify(e);
        dbHealth.markFailure(e);
        if (metrics != null) {
          metrics.recordModuleOperation(false, code);
        }
        LOG.warn(
            "(holarki) code={} op={} attempt={} message={} sqlState={} vendor={}",
            code,
            "moduleDb.withRetry",
            i + 1,
            e.getMessage(),
            e.getSQLState(),
            e.getErrorCode(),
            e);
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
      if (checkHealth && !dbHealth.allowWrite("moduleDb.releaseAdvisoryLock")) {
        return;
      }
      try (Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        ps.setString(1, name);
        ps.executeQuery();
        dbHealth.markSuccess();
        if (metrics != null) {
          metrics.recordModuleOperation(true, null);
        }
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      if (metrics != null) {
        metrics.recordModuleOperation(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={} lock={}",
          code,
          "moduleDb.releaseAdvisoryLock",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          name,
          e);
    } finally {
      heldLocks.remove(name);
    }
  }

  private static String validateLockName(String name) {
    String trimmed = name == null ? null : name.trim();
    if (trimmed == null || trimmed.isEmpty()) {
      throw new IllegalArgumentException("lock name must be provided");
    }
    if (!LOCK_NAME_PATTERN.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("invalid advisory lock name: " + name);
    }
    return trimmed;
  }
}
