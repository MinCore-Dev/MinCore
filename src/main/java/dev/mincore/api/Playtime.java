/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Playtime surface exposed by MinCore.
 *
 * <p>This is a lightweight, in-memory tracker intended for admin UX and simple module or automation
 * logic. It does not persist by itself. Bundled modules or operator tooling that need persistence
 * can mirror deltas on quit or snapshot the leaderboard periodically.
 */
public interface Playtime extends AutoCloseable {

  /**
   * Record that a player started a session at "now".
   *
   * @param player player UUID
   */
  void onJoin(UUID player);

  /**
   * Record that a player ended a session at "now". If they had a live session, the elapsed seconds
   * are added to the accumulated total.
   *
   * @param player player UUID
   */
  void onQuit(UUID player);

  /**
   * Total seconds the player has been online (includes any current live session).
   *
   * @param player player UUID
   * @return non-negative seconds
   */
  long seconds(UUID player);

  /**
   * Reset the accumulated seconds for a player. Any active session is restarted at "now" so future
   * queries treat the reset as an immediate fresh start.
   *
   * @param player player UUID
   */
  void reset(UUID player);

  /**
   * Snapshot the top-N players by seconds (includes any current live session).
   *
   * @param limit number of entries to return (clamped 1..1000)
   * @return immutable list sorted by descending seconds
   */
  List<Entry> top(int limit);

  /**
   * Best-effort set of players currently considered online.
   *
   * @return read-only set of UUIDs
   */
  Set<UUID> onlinePlayers();

  /**
   * Human-friendly formatter like "2h 03m 10s".
   *
   * @param seconds duration in seconds
   * @return formatted string
   */
  static String human(long seconds) {
    long s = Math.max(0, seconds);
    long h = s / 3600;
    long m = (s % 3600) / 60;
    long sec = s % 60;
    if (h > 0) return h + "h " + m + "m " + sec + "s";
    if (m > 0) return m + "m " + sec + "s";
    return sec + "s";
  }

  /**
   * Leaderboard entry.
   *
   * @param player player UUID
   * @param seconds seconds at snapshot time
   */
  record Entry(UUID player, long seconds) {
    /**
     * Safe accessor for seconds as a positive value.
     *
     * @return non-negative seconds
     */
    public long secondsNonNegative() {
      return Math.max(0, seconds);
    }

    /**
     * Returns the player UUID as a canonical string.
     *
     * @return player UUID as string
     */
    public String playerString() {
      return Optional.ofNullable(player).map(UUID::toString).orElse("unknown");
    }
  }

  /** Clear any internal state/resources. */
  @Override
  void close();
}
