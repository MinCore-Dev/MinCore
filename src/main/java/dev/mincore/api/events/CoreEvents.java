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
   * @param oldUnits previous balance (units) or {@code -1} if unknown
   * @param newUnits new balance (units) or {@code -1} if unknown
   * @param reason free-form reason
   * @param seq per-player monotonic sequence
   * @param version event schema version
   */
  record BalanceChangedEvent(
      UUID player, long oldUnits, long newUnits, String reason, long seq, int version) {}

  /**
   * Emitted when a player row is first created.
   *
   * @param player player UUID
   * @param seq per-player monotonic sequence
   * @param version event schema version
   */
  record PlayerRegisteredEvent(UUID player, long seq, int version) {}

  /**
   * Emitted when a player's 'seen' timestamp is updated.
   *
   * @param player player UUID
   * @param seq per-player monotonic sequence
   * @param version event schema version
   */
  record PlayerSeenUpdatedEvent(UUID player, long seq, int version) {}
}
