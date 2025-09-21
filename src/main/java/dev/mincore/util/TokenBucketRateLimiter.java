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
 * <p>Call {@link #tryAcquire(String, long)} to consume one token. If a token is available, the call
 * succeeds and one token is removed; otherwise it fails.
 */
public final class TokenBucketRateLimiter {
  private static final class Bucket {
    double tokens;
    long lastS;

    Bucket(double tokens, long lastS) {
      this.tokens = tokens;
      this.lastS = lastS;
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
   * Attempts to consume one token for {@code key} at {@code nowS}.
   *
   * <p>Refills the bucket based on elapsed time since the last attempt, up to the configured
   * capacity, then consumes one token if available.
   *
   * @param key identity for the bucket (e.g., player UUID string)
   * @param nowS current time in epoch seconds
   * @return {@code true} if a token was available and consumed; {@code false} otherwise
   */
  public boolean tryAcquire(String key, long nowS) {
    Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowS));
    synchronized (b) {
      long dt = Math.max(0, nowS - b.lastS);
      b.tokens = Math.min(capacity, b.tokens + dt * refillPerSec);
      b.lastS = nowS;
      if (b.tokens >= 1.0) {
        b.tokens -= 1.0;
        return true;
      }
      return false;
    }
  }
}
