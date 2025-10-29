/* Holarki © 2025 — MIT */
package dev.holarki.util;

import dev.holarki.api.HolarkiApi;
import dev.holarki.api.Players;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Minimal resolver helpers around Holarki Players. */
public final class PlayersX {
  private PlayersX() {}

  /**
   * Resolve an exact online player name to UUID (case-sensitive).
   *
   * @param name exact name
   * @return UUID if present now, else empty
   */
  public static Optional<UUID> resolveNameExact(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    List<Players.PlayerRef> matches = HolarkiApi.players().byNameAll(name);
    if (matches.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(matches.get(0).uuid());
  }

  /**
   * Best-effort current/known name for a UUID (online players first).
   *
   * @param uuid player UUID
   * @return name if known, else empty
   */
  public static Optional<String> nameOf(UUID uuid) {
    return HolarkiApi.players().byUuid(uuid).map(Players.PlayerRef::name);
  }
}
