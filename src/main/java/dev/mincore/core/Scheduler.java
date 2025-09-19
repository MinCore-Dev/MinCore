/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.sql.Connection;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs and runs lightweight background maintenance tasks.
 *
 * <p>Currently performs a single cleanup pass on boot to purge expired idempotency rows.
 */
public final class Scheduler {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  /** Not instantiable. */
  private Scheduler() {}

  /**
   * Installs background tasks on the provided service scheduler.
   *
   * @param services live services container
   */
  public static void install(Services services) {
    services.scheduler().submit(() -> runCleanupOnce(services));
  }

  /** Single pass: delete expired rows from the idempotency table. */
  private static void runCleanupOnce(Services services) {
    try (Connection c = services.database().borrowConnection()) {
      long now = Instant.now().getEpochSecond();
      var ps = c.prepareStatement("DELETE FROM core_requests WHERE expires_at_s < ? LIMIT 5000");
      ps.setLong(1, now);
      int n = ps.executeUpdate();
      if (n > 0) LOG.info("(mincore) cleanup: removed {} expired idempotency rows", n);
    } catch (Exception e) {
      LOG.warn("(mincore) cleanup failed", e);
    }
  }
}
