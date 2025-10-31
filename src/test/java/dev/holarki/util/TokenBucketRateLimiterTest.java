/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

  @Test
  void cleanupEvictsIdleFullBuckets() {
    FakeTicker ticker = new FakeTicker();
    Duration ttl = Duration.ofSeconds(5);
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1.0, ttl, ticker);

    assertTrue(limiter.tryAcquire("player"));
    assertEquals(1, limiter.bucketCount());

    ticker.advance(Duration.ofSeconds(1));
    ticker.advance(ttl.plusSeconds(1));

    assertTrue(limiter.tryAcquire("other"));
    assertEquals(1, limiter.bucketCount(), "Idle bucket should be evicted during cleanup");

    assertTrue(limiter.tryAcquire("player"));
    assertEquals(2, limiter.bucketCount());
  }

  @Test
  void evictionDoesNotGrantExtraTokens() {
    FakeTicker ticker = new FakeTicker();
    Duration ttl = Duration.ofSeconds(3);
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0, ttl, ticker);

    assertTrue(limiter.tryAcquire("player"));
    assertFalse(limiter.tryAcquire("player"));

    ticker.advance(Duration.ofSeconds(1));
    ticker.advance(ttl.plusSeconds(1));

    assertTrue(limiter.tryAcquire("player"));
    assertFalse(limiter.tryAcquire("player"));
  }

  private static final class FakeTicker implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }

    void advance(Duration duration) {
      now += duration.toNanos();
    }
  }
}
