/* MinCore © 2025 — MIT */
package dev.mincore.core;

/**
 * Service locator for all MinCore subsystems used by built-in modules and server operators.
 *
 * <p>Implementations are created once at server boot and exposed to bundled modules and operator
 * automation through {@code dev.mincore.api.MinCoreApi}. Each accessor returns a singleton owned by
 * the core. Call {@link #shutdown()} during server stop to release resources (connection pool,
 * scheduler, etc.).
 */
public interface Services {

  /**
   * Player directory and identity helper.
   *
   * @return the player directory service singleton
   */
  dev.mincore.api.Players players();

  /**
   * Wallet operations for depositing, withdrawing and transferring currency between players.
   *
   * @return the wallet service singleton
   */
  dev.mincore.api.Wallets wallets();

  /**
   * Per-player JSON attributes store (small structured data keyed by player UUID).
   *
   * @return the attributes service singleton
   */
  dev.mincore.api.Attributes attributes();

  /**
   * Event bus surface for core events (e.g., balance changes).
   *
   * @return the event bus facade singleton
   */
  dev.mincore.api.events.CoreEvents events();

  /**
   * Shared database helpers for bundled modules and operator automation (schema utilities, safe
   * statements, etc.).
   *
   * @return the shared module database helper singleton
   */
  dev.mincore.api.storage.ModuleDatabase database();

  /**
   * Background scheduler for maintenance tasks owned by the core (daemon threads).
   *
   * @return the scheduled executor service used by MinCore
   */
  java.util.concurrent.ScheduledExecutorService scheduler();

  /**
   * Internal metrics registry for bundled modules.
   *
   * @return metrics registry or {@code null} when unavailable
   */
  default Metrics metrics() {
    return null;
  }

  /**
   * In-memory playtime tracker API (session starts/stops and aggregation).
   *
   * @return the playtime tracker singleton
   */
  dev.mincore.api.Playtime playtime();

  /**
   * Shuts down background resources and closes the connection pool.
   *
   * <p>This should be invoked during server shutdown. After this call, the accessor methods are no
   * longer guaranteed to be usable.
   *
   * @throws java.io.IOException if closing resources fails
   */
  void shutdown() throws java.io.IOException;
}
