/* MinCore © 2025 — MIT */
package dev.mincore.util;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import dev.mincore.core.Config;
import dev.mincore.core.Services;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles owner-controlled timezone auto-detection using a local GeoIP database. */
public final class TimezoneAutoDetector implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final Path DEFAULT_DB_PATH = Path.of("config", "mincore.geoip.mmdb");

  private final DatabaseReader reader;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Set<String> skippedAddresses =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  private TimezoneAutoDetector(DatabaseReader reader) {
    this.reader = reader;
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
          "(mincore) timezone auto-detect disabled: GeoIP database not found at {} (download GeoLite2 and place it there)",
          dbPath.toAbsolutePath());
      return Optional.empty();
    }
    if (!Files.isReadable(dbPath)) {
      LOG.warn(
          "(mincore) timezone auto-detect disabled: GeoIP database not readable at {}",
          dbPath.toAbsolutePath());
      return Optional.empty();
    }
    try {
      DatabaseReader reader =
          new DatabaseReader.Builder(dbPath.toFile())
              .withCache(new com.maxmind.db.CHMCache())
              .build();
      LOG.info("(mincore) timezone auto-detect enabled using database {}", dbPath.toAbsolutePath());
      return Optional.of(new TimezoneAutoDetector(reader));
    } catch (IOException e) {
      LOG.warn(
          "(mincore) timezone auto-detect disabled: failed to load GeoIP database: {}",
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
    if (!skippedAddresses.add(address.getHostAddress())) {
      // We've already attempted detection for this address in this session.
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
                LOG.debug("(mincore) timezone auto-detect failed: {}", e.getMessage());
              }
            });
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
      LOG.debug("(mincore) timezone lookup error: {}", e.getMessage());
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
