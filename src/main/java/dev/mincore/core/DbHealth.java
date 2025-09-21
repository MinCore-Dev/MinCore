/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.ErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracks database health and manages degraded mode transitions. */
final class DbHealth {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final long REFUSAL_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

  private final DataSource ds;
  private final ScheduledExecutorService scheduler;
  private final int reconnectEveryS;
  private final Config.Db dbConfig;
  private final AtomicBoolean degraded = new AtomicBoolean(false);
  private final AtomicLong lastRefusalLogNs = new AtomicLong(0L);
  private final AtomicBoolean bootstrapScheduled = new AtomicBoolean(false);

  DbHealth(
      DataSource ds, ScheduledExecutorService scheduler, int reconnectEveryS, Config.Db dbConfig) {
    this.ds = ds;
    this.scheduler = scheduler;
    this.reconnectEveryS = Math.max(1, reconnectEveryS);
    this.dbConfig = dbConfig;
    this.scheduler.scheduleWithFixedDelay(
        this::probe, this.reconnectEveryS, this.reconnectEveryS, TimeUnit.SECONDS);
  }

  boolean allowWrite(String operation) {
    if (!degraded.get()) {
      return true;
    }
    long now = System.nanoTime();
    long prev = lastRefusalLogNs.get();
    if (now - prev > REFUSAL_LOG_INTERVAL_NS && lastRefusalLogNs.compareAndSet(prev, now)) {
      LOG.warn(
          "(mincore) code={} op={} message={}",
          ErrorCode.DEGRADED_MODE,
          operation,
          "database is in degraded mode");
    }
    return false;
  }

  void markFailure(Throwable cause) {
    if (degraded.compareAndSet(false, true)) {
      LOG.warn(
          "(mincore) code={} op={} message={}",
          ErrorCode.CONNECTION_LOST,
          "db.health",
          cause != null ? cause.getMessage() : "database unavailable",
          cause);
    }
    maybeBootstrap(cause);
  }

  void markSuccess() {
    if (degraded.compareAndSet(true, false)) {
      LOG.info(
          "(mincore) code={} op={} message={}",
          ErrorCode.DEGRADED_MODE,
          "db.health",
          "database recovered; leaving degraded mode");
    }
    bootstrapScheduled.set(false);
  }

  boolean isDegraded() {
    return degraded.get();
  }

  private void probe() {
    if (!degraded.get()) {
      return;
    }
    try (Connection c = ds.getConnection();
        PreparedStatement read = c.prepareStatement("SELECT 1");
        PreparedStatement write =
            c.prepareStatement("UPDATE core_requests SET expires_at_s = expires_at_s WHERE 1=0")) {
      try (ResultSet rs = read.executeQuery()) {
        while (rs.next()) {
          // drain result set to ensure the read completes
        }
      }
      write.executeUpdate();
      markSuccess();
    } catch (Exception e) {
      maybeBootstrap(e);
      // Still degraded; swallow to avoid log spam.
    }
  }

  private void maybeBootstrap(Throwable cause) {
    if (dbConfig == null) {
      return;
    }
    SQLException sql = unwrapSql(cause);
    if (sql == null || !DbBootstrap.isUnknownDatabase(sql)) {
      return;
    }
    if (bootstrapScheduled.compareAndSet(false, true)) {
      scheduler.execute(
          () -> {
            try {
              DbBootstrap.ensureDatabaseExists(
                  dbConfig.jdbcUrl(), dbConfig.user(), dbConfig.password());
            } catch (SQLException e) {
              LOG.warn(
                  "(mincore) code={} op={} message={}",
                  ErrorCode.CONNECTION_LOST,
                  "db.bootstrap",
                  e.getMessage(),
                  e);
              bootstrapScheduled.set(false);
            }
          });
    }
  }

  private SQLException unwrapSql(Throwable cause) {
    Throwable cursor = cause;
    while (cursor != null) {
      if (cursor instanceof SQLException sql) {
        return sql;
      }
      cursor = cursor.getCause();
    }
    return null;
  }
}
