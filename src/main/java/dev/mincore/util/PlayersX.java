/* MinCore © 2025 — MIT */
package dev.mincore.util;

import dev.mincore.api.MinCoreApi;
import java.util.Optional;
import java.util.UUID;

/** Minimal resolver helpers around MinCore Players. */
public final class PlayersX {
  private PlayersX() {}

  /**
   * Resolve an exact online player name to UUID (case-sensitive).
   *
   * @param name exact name
   * @return UUID if present now, else empty
   */
  public static Optional<UUID> resolveNameExact(String name) {
    return MinCoreApi.players().getPlayerByName(name).map(v -> v.uuid());
  }

  /**
   * Best-effort current/known name for a UUID (online players first).
   *
   * @param uuid player UUID
   * @return name if known, else empty
   */
  public static Optional<String> nameOf(UUID uuid) {
    return MinCoreApi.players().getPlayer(uuid).map(v -> v.name());
  }
}
