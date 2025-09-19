/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.events.CoreEvents;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Simple in-process event bus for core events.
 *
 * <p>Thread-safe via {@link CopyOnWriteArrayList}. Handlers should be short and non-blocking.
 */
public final class EventBus implements CoreEvents {
  private final List<Consumer<BalanceChangedEvent>> bal = new CopyOnWriteArrayList<>();
  private final List<Consumer<PlayerRegisteredEvent>> reg = new CopyOnWriteArrayList<>();
  private final List<Consumer<PlayerSeenUpdatedEvent>> seen = new CopyOnWriteArrayList<>();

  /** Creates a new event bus. */
  public EventBus() {}

  @Override
  public AutoCloseable onBalanceChanged(Consumer<BalanceChangedEvent> h) {
    bal.add(h);
    return () -> bal.remove(h);
  }

  @Override
  public AutoCloseable onPlayerRegistered(Consumer<PlayerRegisteredEvent> h) {
    reg.add(h);
    return () -> reg.remove(h);
  }

  @Override
  public AutoCloseable onPlayerSeenUpdated(Consumer<PlayerSeenUpdatedEvent> h) {
    seen.add(h);
    return () -> seen.remove(h);
  }

  /**
   * Dispatches a balance changed event to all subscribers.
   *
   * @param e event payload
   */
  public void fireBalanceChanged(BalanceChangedEvent e) {
    for (var h : bal) {
      try {
        h.accept(e);
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Dispatches a player registered event to all subscribers.
   *
   * @param e event payload
   */
  public void firePlayerRegistered(PlayerRegisteredEvent e) {
    for (var h : reg) {
      try {
        h.accept(e);
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Dispatches a player seen updated event to all subscribers.
   *
   * @param e event payload
   */
  public void firePlayerSeenUpdated(PlayerSeenUpdatedEvent e) {
    for (var h : seen) {
      try {
        h.accept(e);
      } catch (Throwable ignored) {
      }
    }
  }
}
