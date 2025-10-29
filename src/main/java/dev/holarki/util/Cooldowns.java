/* Holarki © 2025 — MIT */
package dev.holarki.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny in-memory cooldown map (key → next-allowed epoch second).
 *
 * <p>Use when you want "one action per X seconds" behavior per key. This utility is thread-safe and
 * requires callers to supply the current epoch-second timestamp.
 */
public final class Cooldowns {
  private final Map<String, Long> nextAt = new ConcurrentHashMap<>();

  /** Creates an empty cooldown registry. */
  public Cooldowns() {}

  /**
   * Attempts to acquire permission to act at {@code nowS} for the given {@code key}.
   *
   * <p>If the cooldown has elapsed (or no cooldown is recorded yet), this method returns {@code
   * true} and records the next eligible time as {@code nowS + max(0, cooldownSeconds)}. Otherwise
   * returns {@code false}.
   *
   * @param key identity to throttle (e.g., player UUID string, command name)
   * @param cooldownSeconds minimum seconds between allowed actions for this key
   * @param nowS current time in epoch seconds
   * @return {@code true} if allowed now and the cooldown was (re)started; {@code false} otherwise
   */
  public boolean tryAcquire(String key, long cooldownSeconds, long nowS) {
    long clampedCooldown = Math.max(0L, cooldownSeconds);
    AtomicBoolean acquired = new AtomicBoolean(false);
    nextAt.compute(
        key,
        (ignoredKey, existingNext) -> {
          long nextAllowed = existingNext == null ? 0L : existingNext;
          if (nowS < nextAllowed) {
            return nextAllowed;
          }
          acquired.set(true);
          return nowS + clampedCooldown;
        });
    return acquired.get();
  }

  /**
   * Clears any stored cooldown for {@code key}, allowing immediate use on the next attempt.
   *
   * @param key identity to reset
   */
  public void reset(String key) {
    nextAt.remove(key);
  }
}
