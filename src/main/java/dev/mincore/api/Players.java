/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Player directory backed by the core database. */
public interface Players {
  /**
   * Looks up a player by UUID.
   *
   * @param uuid player UUID
   * @return player reference if present
   */
  Optional<PlayerRef> byUuid(UUID uuid);

  /**
   * Looks up a player by case-insensitive name.
   *
   * @param name exact username (case preserved)
   * @return player reference if present
   */
  Optional<PlayerRef> byName(String name);

  /**
   * Returns all players that share the provided case-insensitive name.
   *
   * @param name username to match (case insensitive)
   * @return immutable list of player references
   */
  List<PlayerRef> byNameAll(String name);

  /**
   * Upserts the player row and "seen" timestamp.
   *
   * @param uuid player UUID
   * @param name latest username
   * @param seenAtS last seen time in epoch seconds (UTC)
   */
  void upsertSeen(UUID uuid, String name, long seenAtS);

  /**
   * Iterates over all players in primary-key order.
   *
   * @param consumer callback for each player reference
   */
  void iteratePlayers(Consumer<PlayerRef> consumer);

  /** Immutable projection of a player row. */
  interface PlayerRef {
    /**
     * Player UUID.
     *
     * @return player UUID
     */
    UUID uuid();

    /**
     * Last known username.
     *
     * @return username string
     */
    String name();

    /**
     * Creation time (epoch seconds, UTC).
     *
     * @return creation timestamp
     */
    long createdAtS();

    /**
     * Last update time (epoch seconds, UTC).
     *
     * @return update timestamp
     */
    long updatedAtS();

    /**
     * Last seen time (epoch seconds, UTC) or {@code null}.
     *
     * @return seen timestamp or {@code null}
     */
    Long seenAtS();

    /**
     * Wallet balance in smallest currency units.
     *
     * @return balance in minor units
     */
    long balanceUnits();
  }
}
