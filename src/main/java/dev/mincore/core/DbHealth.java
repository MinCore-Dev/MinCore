/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.sql.Connection;
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
  private final AtomicBoolean degraded = new AtomicBoolean(false);
  private final AtomicLong lastRefusalLogNs = new AtomicLong(0L);

  DbHealth(DataSource ds, ScheduledExecutorService scheduler, int reconnectEveryS) {
    this.ds = ds;
    this.scheduler = scheduler;
    this.reconnectEveryS = Math.max(1, reconnectEveryS);
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
      LOG.warn("(mincore) refusing {} while database is degraded", operation);
    }
    return false;
  }

  void markFailure(Throwable cause) {
    if (degraded.compareAndSet(false, true)) {
      LOG.warn(
          "(mincore) database unavailable; entering degraded mode ({})",
          cause != null ? cause.getMessage() : "unknown");
    }
  }

  void markSuccess() {
    if (degraded.compareAndSet(true, false)) {
      LOG.info("(mincore) database recovered; leaving degraded mode");
    }
  }

  boolean isDegraded() {
    return degraded.get();
  }

  private void probe() {
    if (!degraded.get()) {
      return;
    }
    try (Connection ignored = ds.getConnection()) {
      markSuccess();
    } catch (Exception e) {
      // Still degraded; swallow to avoid log spam.
    }
  }
}
