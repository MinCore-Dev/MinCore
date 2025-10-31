/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
 * <p>Idle buckets that remain completely full for {@code 5 minutes} are evicted automatically.
 * This keeps the limiter's memory usage bounded even if new keys stop using their buckets.
 *
 * <p>Call {@link #tryAcquire(String)} to consume one token. If a token is available, the call
 * succeeds and one token is removed; otherwise it fails. The limiter uses {@link System#nanoTime()}
 * internally to avoid issues when the system clock jumps.
 */
public final class TokenBucketRateLimiter {
  private static final class Bucket {
    double tokens;
    long lastRefillNanos;
    long lastUsedNanos;

    Bucket(double tokens, long nowNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = nowNanos;
      this.lastUsedNanos = nowNanos;
    }
  }

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final double capacity;
  private final double refillPerSec;
  private final long bucketTtlNanos;
  private final long cleanupIntervalNanos;
  private final LongSupplier nanoTimeSource;
  private final AtomicLong nextCleanupNanos;

  private static final double FULL_EPSILON = 1e-9;
  private static final Duration DEFAULT_BUCKET_TTL = Duration.ofMinutes(5);

  /**
   * Creates a token bucket limiter.
   *
   * @param capacity maximum number of tokens a bucket can hold (rounded up to at least {@code 1})
   * @param refillPerSec tokens added per second (must be {@code > 0}; fractional allowed)
   */
  public TokenBucketRateLimiter(int capacity, double refillPerSec) {
    this(capacity, refillPerSec, DEFAULT_BUCKET_TTL, System::nanoTime);
  }

  /**
   * Creates a token bucket limiter with a custom idle eviction policy.
   *
   * @param capacity maximum number of tokens a bucket can hold (rounded up to at least {@code 1})
   * @param refillPerSec tokens added per second (must be {@code > 0}; fractional allowed)
   * @param bucketTtl how long an untouched, completely refilled bucket is retained before eviction
   */
  public TokenBucketRateLimiter(int capacity, double refillPerSec, Duration bucketTtl) {
    this(capacity, refillPerSec, bucketTtl, System::nanoTime);
  }

  TokenBucketRateLimiter(
      int capacity,
      double refillPerSec,
      Duration bucketTtl,
      LongSupplier nanoTimeSource) {
    Objects.requireNonNull(bucketTtl, "bucketTtl");
    this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
    this.capacity = Math.max(1, capacity);
    this.refillPerSec = Math.max(0.0001, refillPerSec);
    this.bucketTtlNanos = Math.max(0L, bucketTtl.toNanos());
    this.cleanupIntervalNanos = bucketTtlNanos;
    long initialNow = this.nanoTimeSource.getAsLong();
    long nextCleanup =
        cleanupIntervalNanos == 0 ? Long.MAX_VALUE : initialNow + cleanupIntervalNanos;
    this.nextCleanupNanos = new AtomicLong(nextCleanup);
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
    long nowNanos = nanoTimeSource.getAsLong();
    maybeCleanup(nowNanos);
    Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowNanos));
    synchronized (b) {
      refillBucket(b, nowNanos);
      if (b.tokens >= 1.0) {
        b.tokens -= 1.0;
        b.lastUsedNanos = nowNanos;
        return true;
      }
      return false;
    }
  }

  int bucketCount() {
    return buckets.size();
  }

  private void refillBucket(Bucket bucket, long nowNanos) {
    long delta = nowNanos - bucket.lastRefillNanos;
    if (delta > 0L) {
      double elapsedSeconds = delta / 1_000_000_000.0d;
      bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSec);
      bucket.lastRefillNanos = nowNanos;
    }
  }

  private void maybeCleanup(long nowNanos) {
    if (bucketTtlNanos == 0 || cleanupIntervalNanos == 0) {
      return;
    }
    long next = nextCleanupNanos.get();
    if (nowNanos < next) {
      return;
    }
    if (!nextCleanupNanos.compareAndSet(next, nowNanos + cleanupIntervalNanos)) {
      return;
    }
    buckets.entrySet()
        .removeIf(
            entry -> {
              Bucket bucket = entry.getValue();
              synchronized (bucket) {
                refillBucket(bucket, nowNanos);
                if (bucket.tokens >= capacity - FULL_EPSILON) {
                  long idleNanos = nowNanos - bucket.lastUsedNanos;
                  return idleNanos >= bucketTtlNanos;
                }
              }
              return false;
            });
  }
}
