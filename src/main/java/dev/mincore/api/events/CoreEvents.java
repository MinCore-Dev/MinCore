/* MinCore © 2025 — MIT */
package dev.mincore.api.events;

import java.util.UUID;

/**
 * Lightweight in-process event bus surface for core events.
 *
 * <p>Handlers run in-process on the server thread that fires them. Add-ons should keep handlers
 * short and resilient.
 */
public interface CoreEvents {
  /**
   * Subscribes to balance changes.
   *
   * @param h handler
   * @return a handle to close and unsubscribe
   */
  AutoCloseable onBalanceChanged(java.util.function.Consumer<BalanceChangedEvent> h);

  /**
   * Subscribes to first-time player registration.
   *
   * @param h handler
   * @return a handle to close and unsubscribe
   */
  AutoCloseable onPlayerRegistered(java.util.function.Consumer<PlayerRegisteredEvent> h);

  /**
   * Subscribes to 'last seen' timestamp updates.
   *
   * @param h handler
   * @return a handle to close and unsubscribe
   */
  AutoCloseable onPlayerSeenUpdated(java.util.function.Consumer<PlayerSeenUpdatedEvent> h);

  /**
   * Emitted when a player's balance changes.
   *
   * @param player player UUID
   * @param seq per-player monotonic sequence number
   * @param oldUnits previous balance (units)
   * @param newUnits new balance (units)
   * @param reason normalized reason string
   * @param version event schema version
   */
  record BalanceChangedEvent(
      UUID player, long seq, long oldUnits, long newUnits, String reason, int version) {}

  /**
   * Emitted when a player row is first created.
   *
   * @param player player UUID
   * @param seq per-player monotonic sequence number
   * @param name registered username
   * @param version event schema version
   */
  record PlayerRegisteredEvent(UUID player, long seq, String name, int version) {}

  /**
   * Emitted when a player's 'seen' timestamp is updated.
   *
   * @param player player UUID
   * @param seq per-player monotonic sequence number
   * @param oldName previous username
   * @param newName new username
   * @param seenAtS new seen timestamp (epoch seconds, UTC)
   * @param version event schema version
   */
  record PlayerSeenUpdatedEvent(
      UUID player, long seq, String oldName, String newName, long seenAtS, int version) {}
}
