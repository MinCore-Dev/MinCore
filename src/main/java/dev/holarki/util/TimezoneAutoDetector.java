/* Holarki © 2025 — MIT */
package dev.holarki.util;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles owner-controlled timezone auto-detection using a local GeoIP database.
 *
 * <p>Detections are rate-limited per player/IP pair so multiple players behind the same address
 * can still receive automatic zones while preventing redundant lookups for the same player. Each
 * recorded attempt expires after a configurable interval so players can be re-detected later.
 */
public final class TimezoneAutoDetector implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final Path DEFAULT_DB_PATH = Path.of("config", "holarki.geoip.mmdb");

  /** Default duration to remember detection attempts per player/IP pair. */
  private static final Duration DEFAULT_DETECTION_TTL = Duration.ofHours(12);

  private final DatabaseReader reader;
  private final Duration detectionTtl;
  private final ConcurrentMap<String, ConcurrentMap<UUID, Instant>> processedPairs =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private TimezoneAutoDetector(DatabaseReader reader) {
    this(reader, DEFAULT_DETECTION_TTL);
  }

  private TimezoneAutoDetector(DatabaseReader reader, Duration detectionTtl) {
    this.reader = reader;
    this.detectionTtl =
        detectionTtl == null || detectionTtl.isNegative()
            ? DEFAULT_DETECTION_TTL
            : detectionTtl;
  }

  /**
   * Attempts to initialize the detector if enabled by configuration.
   *
   * @param cfg runtime configuration
   * @return optional detector that can perform lookups when present
   */
  public static Optional<TimezoneAutoDetector> create(Config cfg) {
    if (cfg == null) {
      return Optional.empty();
    }

    Config.TimezoneModule tzModule = cfg.modules().timezone();
    if (tzModule == null || !tzModule.enabled()) {
      return Optional.empty();
    }

    Config.AutoDetect autoCfg = tzModule.autoDetect();
    if (autoCfg == null || !autoCfg.enabled() || !cfg.time().display().autoDetect()) {
      return Optional.empty();
    }

    Path dbPath =
        autoCfg.databasePath() != null && !autoCfg.databasePath().isBlank()
            ? Path.of(autoCfg.databasePath())
            : DEFAULT_DB_PATH;
    if (!Files.exists(dbPath)) {
      LOG.warn(
          "(holarki) timezone auto-detect disabled: GeoIP database not found at {} (download GeoLite2 and place it there)",
          dbPath.toAbsolutePath());
      return Optional.empty();
    }
    if (!Files.isReadable(dbPath)) {
      LOG.warn(
          "(holarki) timezone auto-detect disabled: GeoIP database not readable at {}",
          dbPath.toAbsolutePath());
      return Optional.empty();
    }
    try {
      DatabaseReader reader =
          new DatabaseReader.Builder(dbPath.toFile())
              .withCache(new com.maxmind.db.CHMCache())
              .build();
      LOG.info("(holarki) timezone auto-detect enabled using database {}", dbPath.toAbsolutePath());
      return Optional.of(new TimezoneAutoDetector(reader));
    } catch (IOException e) {
      LOG.warn(
          "(holarki) timezone auto-detect disabled: failed to load GeoIP database: {}",
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Schedules an asynchronous auto-detection attempt for the provided player.
   *
   * @param services service container for attribute persistence and executor access
   * @param uuid player identifier
   * @param ipString remote IP string reported by the connection
   */
  public void scheduleDetect(Services services, UUID uuid, String ipString) {
    if (services == null || uuid == null || reader == null || closed.get()) {
      return;
    }
    InetAddress address = parseAddress(ipString);
    if (address == null
        || address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()) {
      return;
    }
    Instant now = Instant.now();
    if (!shouldAttemptDetection(uuid, address, now)) {
      // We've already attempted detection for this player and address recently.
      return;
    }
    services
        .scheduler()
        .execute(
            () -> {
              try {
                if (closed.get()) {
                  return;
                }
                if (Timezones.resolve(uuid, services).isPresent()) {
                  return;
                }
                Optional<ZoneId> zone = lookup(address);
                zone.ifPresent(z -> Timezones.setAuto(uuid, z, services));
              } catch (Exception e) {
                LOG.debug("(holarki) timezone auto-detect failed: {}", e.getMessage());
              } finally {
                prune(uuid, address, Instant.now());
              }
            });
  }

  /**
   * Returns whether a detection attempt should proceed for the given player/address and records
   * the attempt timestamp when allowed.
   */
  boolean shouldAttemptDetection(UUID uuid, InetAddress address, Instant now) {
    if (uuid == null || address == null || now == null) {
      return false;
    }
    String key = address.getHostAddress();
    ConcurrentMap<UUID, Instant> players =
        processedPairs.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
    Instant cutoff = now.minus(detectionTtl);
    players.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    Instant previous = players.get(uuid);
    if (previous != null && !previous.isBefore(cutoff)) {
      return false;
    }
    players.put(uuid, now);
    return true;
  }

  private void prune(UUID uuid, InetAddress address, Instant reference) {
    if (uuid == null || address == null || reference == null) {
      return;
    }
    String key = address.getHostAddress();
    ConcurrentMap<UUID, Instant> players = processedPairs.get(key);
    if (players == null) {
      return;
    }
    Instant cutoff = reference.minus(detectionTtl);
    players.compute(
        uuid,
        (ignored, previous) ->
            previous == null || reference.isAfter(previous) ? reference : previous);
    players.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    if (players.isEmpty()) {
      processedPairs.remove(key, players);
    }
  }

  /** Creates a detector instance with a custom TTL for unit tests. */
  static TimezoneAutoDetector forTesting(Duration detectionTtl) {
    return new TimezoneAutoDetector(null, detectionTtl);
  }

  private InetAddress parseAddress(String ip) {
    if (ip == null) {
      return null;
    }
    String sanitized = ip.trim();
    if (sanitized.isEmpty()) {
      return null;
    }
    if (sanitized.startsWith("/")) {
      sanitized = sanitized.substring(1);
    }
    int slash = sanitized.indexOf('/');
    if (slash >= 0 && slash < sanitized.length() - 1) {
      sanitized = sanitized.substring(slash + 1);
    }
    if (sanitized.startsWith("[")) {
      int end = sanitized.indexOf(']');
      if (end > 0) {
        sanitized = sanitized.substring(1, end);
      }
    } else {
      int lastColon = sanitized.lastIndexOf(':');
      if (lastColon > 0 && sanitized.indexOf(':') == lastColon) {
        sanitized = sanitized.substring(0, lastColon);
      }
    }
    if (sanitized.isEmpty()) {
      return null;
    }
    try {
      return InetAddress.getByName(sanitized);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private Optional<ZoneId> lookup(InetAddress address) {
    try {
      var response = reader.city(address);
      if (response == null || response.getLocation() == null) {
        return Optional.empty();
      }
      String tz = response.getLocation().getTimeZone();
      if (tz == null || tz.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(ZoneId.of(tz));
    } catch (GeoIp2Exception | IOException e) {
      LOG.debug("(holarki) timezone lookup error: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void close() {
    if (reader != null && closed.compareAndSet(false, true)) {
      try {
        reader.close();
      } catch (IOException ignored) {
      }
    }
  }
}
