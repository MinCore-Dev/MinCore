/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Player directory backed by the core database.
 *
 * <p>Provides identity lookups and iteration helpers. Names are normalized to a lowercase column
 * for fast case-insensitive lookups.
 */
public interface Players {
  /**
   * Ensures an account row exists for {@code uuid}; updates name/seen time.
   *
   * @param uuid player UUID
   * @param name current username
   */
  void ensureAccount(UUID uuid, String name);

  /**
   * Updates the stored player name if it changed.
   *
   * @param uuid player UUID
   * @param name new username
   */
  void syncName(UUID uuid, String name);

  /**
   * Looks up a player by UUID.
   *
   * @param uuid player UUID
   * @return a view if found
   */
  Optional<PlayerView> getPlayer(UUID uuid);

  /**
   * Looks up a player by exact name (case-insensitive internally).
   *
   * @param exactName username to match
   * @return a view if found
   */
  Optional<PlayerView> getPlayerByName(String exactName);

  /**
   * Iterates players in batches to avoid loading the entire table in memory.
   *
   * @param consumer callback for each row
   * @param batchSize max rows per batch (e.g., 500–5000)
   */
  void iteratePlayers(Consumer<PlayerView> consumer, int batchSize);

  /** Immutable projection of a player row. */
  interface PlayerView {
    /**
     * Returns the player UUID.
     *
     * @return player UUID
     */
    UUID uuid();

    /**
     * Returns the last known username.
     *
     * @return username
     */
    String name();

    /**
     * Returns the creation time in epoch seconds.
     *
     * @return creation time (epoch seconds)
     */
    long createdAtEpochSeconds();

    /**
     * Returns the last update time in epoch seconds.
     *
     * @return last update time (epoch seconds)
     */
    long updatedAtEpochSeconds();

    /**
     * Returns the last seen time in epoch seconds, or {@code null} if unknown.
     *
     * @return last seen time (epoch seconds) or {@code null}
     */
    Long seenAtEpochSeconds();

    /**
     * Returns the wallet balance in smallest currency units.
     *
     * @return balance (units)
     */
    long balanceUnits();
  }
}
