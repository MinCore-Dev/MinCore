/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.events.CoreEvents;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Asynchronous in-process event bus for core events.
 *
 * <p>Each player has a dedicated serial queue to guarantee in-order delivery while still allowing
 * concurrent dispatch for different players.
 */
public final class EventBus implements CoreEvents, AutoCloseable {
  private final List<Consumer<BalanceChangedEvent>> bal = new CopyOnWriteArrayList<>();
  private final List<Consumer<PlayerRegisteredEvent>> reg = new CopyOnWriteArrayList<>();
  private final List<Consumer<PlayerSeenUpdatedEvent>> seen = new CopyOnWriteArrayList<>();
  private final Map<UUID, PlayerQueue> queues = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** Creates a new event bus with a daemon thread pool sized for the host. */
  public EventBus() {
    this(createExecutor());
  }

  private EventBus(ExecutorService executor) {
    this.executor = executor;
  }

  private static ExecutorService createExecutor() {
    int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r, "holarki-events");
          t.setDaemon(true);
          return t;
        };
    return Executors.newFixedThreadPool(threads, factory);
  }

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
   * Dispatches a balance changed event asynchronously.
   *
   * @param e event to dispatch
   */
  public void fireBalanceChanged(BalanceChangedEvent e) {
    if (e == null || closed.get()) return;
    enqueue(e.player(), () -> dispatch(bal, e));
  }

  /**
   * Dispatches a player registered event asynchronously.
   *
   * @param e event to dispatch
   */
  public void firePlayerRegistered(PlayerRegisteredEvent e) {
    if (e == null || closed.get()) return;
    enqueue(e.player(), () -> dispatch(reg, e));
  }

  /**
   * Dispatches a player seen updated event asynchronously.
   *
   * @param e event to dispatch
   */
  public void firePlayerSeenUpdated(PlayerSeenUpdatedEvent e) {
    if (e == null || closed.get()) return;
    enqueue(e.player(), () -> dispatch(seen, e));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      executor.shutdownNow();
      queues.clear();
      bal.clear();
      reg.clear();
      seen.clear();
    }
  }

  private <T> void dispatch(List<Consumer<T>> handlers, T event) {
    for (Consumer<T> handler : handlers) {
      try {
        handler.accept(event);
      } catch (Throwable ignored) {
        // Handlers are isolated; ignore individual failures.
      }
    }
  }

  private void enqueue(UUID player, Runnable task) {
    if (player == null) {
      executor.execute(task);
      return;
    }
    PlayerQueue queue = queues.computeIfAbsent(player, id -> new PlayerQueue());
    queue.tasks.add(task);
    if (queue.draining.compareAndSet(false, true)) {
      executor.execute(() -> drain(player, queue));
    }
  }

  private void drain(UUID player, PlayerQueue queue) {
    while (true) {
      Runnable next = queue.tasks.poll();
      if (next == null) {
        if (queue.draining.compareAndSet(true, false)) {
          if (queue.tasks.isEmpty()) {
            if (queues.remove(player, queue)) {
              Runnable extra;
              while ((extra = queue.tasks.poll()) != null) {
                enqueue(player, extra);
              }
            }
            return;
          }
          if (!queue.draining.compareAndSet(false, true)) {
            return;
          }
          continue;
        }
        return;
      }
      try {
        next.run();
      } catch (Throwable ignored) {
      }
    }
  }

  private static final class PlayerQueue {
    final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    final AtomicBoolean draining = new AtomicBoolean(false);
  }
}
