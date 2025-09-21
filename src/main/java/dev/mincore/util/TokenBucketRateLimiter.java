/* MinCore © 2025 — MIT */
package dev.mincore.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, thread-safe token bucket rate limiter.
 *
 * <p>Each {@code key} has its own bucket with:
 *
 * <ul>
 *   <li><strong>capacity</strong>: maximum tokens that can be stored
 *   <li><strong>refillPerSec</strong>: tokens added per second (fractional allowed)
 * </ul>
 *
 * <p>Call {@link #tryAcquire(String)} to consume one token. If a token is available, the call
 * succeeds and one token is removed; otherwise it fails. The limiter uses {@link System#nanoTime()}
 * internally to avoid issues when the system clock jumps.
 */
public final class TokenBucketRateLimiter {
  private static final class Bucket {
    double tokens;
    long lastNanos;

    Bucket(double tokens, long lastNanos) {
      this.tokens = tokens;
      this.lastNanos = lastNanos;
    }
  }

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final double capacity;
  private final double refillPerSec;

  /**
   * Creates a token bucket limiter.
   *
   * @param capacity maximum number of tokens a bucket can hold (rounded up to at least {@code 1})
   * @param refillPerSec tokens added per second (must be {@code > 0}; fractional allowed)
   */
  public TokenBucketRateLimiter(int capacity, double refillPerSec) {
    this.capacity = Math.max(1, capacity);
    this.refillPerSec = Math.max(0.0001, refillPerSec);
  }

  /**
   * Attempts to consume one token for {@code key} using a monotonic time source.
   *
   * <p>Refills the bucket based on elapsed time since the last attempt, up to the configured
   * capacity, then consumes one token if available.
   *
   * @param key identity for the bucket (e.g., player UUID string)
   * @return {@code true} if a token was available and consumed; {@code false} otherwise
   */
  public boolean tryAcquire(String key) {
    long nowNanos = System.nanoTime();
    Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowNanos));
    synchronized (b) {
      long delta = nowNanos - b.lastNanos;
      if (delta > 0L) {
        double elapsedSeconds = delta / 1_000_000_000.0d;
        b.tokens = Math.min(capacity, b.tokens + elapsedSeconds * refillPerSec);
        b.lastNanos = nowNanos;
      }
      if (b.tokens >= 1.0) {
        b.tokens -= 1.0;
        return true;
      }
      return false;
    }
  }
}
