/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimezoneAutoDetectorTest {

  @Test
  void allowsMultiplePlayersSharingIp() throws Exception {
    TimezoneAutoDetector detector = TimezoneAutoDetector.forTesting(Duration.ofMinutes(5));
    InetAddress address = InetAddress.getByName("203.0.113.5");
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant now = Instant.now();

    assertTrue(detector.shouldAttemptDetection(first, address, now));
    assertTrue(detector.shouldAttemptDetection(second, address, now));
    assertFalse(detector.shouldAttemptDetection(first, address, now.plusSeconds(30)));
  }

  @Test
  void allowsRetryAfterTtlExpires() throws Exception {
    Duration ttl = Duration.ofMinutes(2);
    TimezoneAutoDetector detector = TimezoneAutoDetector.forTesting(ttl);
    InetAddress address = InetAddress.getByName("198.51.100.42");
    UUID player = UUID.randomUUID();
    Instant start = Instant.now();

    assertTrue(detector.shouldAttemptDetection(player, address, start));
    assertFalse(detector.shouldAttemptDetection(player, address, start.plus(ttl).minusSeconds(1)));
    assertTrue(detector.shouldAttemptDetection(player, address, start.plus(ttl).plusSeconds(1)));
  }
}
