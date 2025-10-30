/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.maxmind.geoip2.DatabaseReader;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimezoneAutoDetectorTest {
  private Path tempDir;

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

  @Test
  void createRejectsNonRegularFiles() throws Exception {
    tempDir = Files.createTempDirectory("holarki-tz-test");
    Config config = configWithGeoIpDatabase(tempDir);
    assertTrue(TimezoneAutoDetector.create(config).isEmpty());
  }

  @Test
  void schedulerRejectionDoesNotThrottleFutureAttempts() throws Exception {
    TimezoneAutoDetector detector =
        TimezoneAutoDetector.forTesting(mock(DatabaseReader.class), Duration.ofHours(1));
    Services services = new RejectingServices();
    InetAddress address = InetAddress.getByName("198.51.100.20");
    UUID player = UUID.randomUUID();

    detector.scheduleDetect(services, player, address.getHostAddress());

    assertTrue(detector.shouldAttemptDetection(player, address, Instant.now()));
  }

  @Test
  void existingZoneDoesNotThrottleFutureDetectionAttempts() throws Exception {
    UUID player = UUID.randomUUID();
    InetAddress address = InetAddress.getByName("198.51.100.30");
    InMemoryServices services = new InMemoryServices();
    TimezoneAutoDetector detector =
        TimezoneAutoDetector.forTesting(mock(DatabaseReader.class), Duration.ofHours(1));

    Timezones.set(player, ZoneId.of("Europe/Berlin"), services);

    detector.scheduleDetect(services, player, address.getHostAddress());

    assertTrue(detector.shouldAttemptDetection(player, address, Instant.now()));
  }

  @Test
  void autoDetectedZoneAllowsFutureDetectionAttempts() throws Exception {
    UUID player = UUID.randomUUID();
    InetAddress address = InetAddress.getByName("198.51.100.31");
    InMemoryServices services = new InMemoryServices();
    TimezoneAutoDetector detector =
        TimezoneAutoDetector.forTesting(mock(DatabaseReader.class), Duration.ofHours(1));

    Timezones.setAuto(player, ZoneId.of("America/Chicago"), services);

    detector.scheduleDetect(services, player, address.getHostAddress());

    assertFalse(detector.shouldAttemptDetection(player, address, Instant.now()));
  }

  @AfterEach
  void cleanup() throws Exception {
    if (tempDir != null) {
      Files.deleteIfExists(tempDir);
      tempDir = null;
    }
  }

  private static final class RejectingServices implements Services {
    private final ScheduledExecutorService scheduler = new RejectingScheduler();
    private final dev.holarki.api.Attributes attributes = new NoopAttributes();

    @Override
    public dev.holarki.api.Players players() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.Wallets wallets() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.Attributes attributes() {
      return attributes;
    }

    @Override
    public dev.holarki.api.events.CoreEvents events() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.storage.ModuleDatabase database() {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.concurrent.ScheduledExecutorService scheduler() {
      return scheduler;
    }

    @Override
    public dev.holarki.core.Metrics metrics() {
      return null;
    }

    @Override
    public dev.holarki.api.Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}
  }

  private static final class NoopAttributes implements dev.holarki.api.Attributes {
    @Override
    public Optional<String> get(UUID owner, String key) {
      return Optional.empty();
    }

    @Override
    public void put(UUID owner, String key, String jsonValue, long nowS) {}

    @Override
    public void remove(UUID owner, String key) {}
  }

  private static final class RejectingScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("rejected");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class InMemoryServices implements Services {
    private final ScheduledExecutorService scheduler = new ImmediateScheduler();
    private final InMemoryAttributes attributes = new InMemoryAttributes();

    @Override
    public dev.holarki.api.Players players() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.Wallets wallets() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.Attributes attributes() {
      return attributes;
    }

    @Override
    public dev.holarki.api.events.CoreEvents events() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.storage.ModuleDatabase database() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService scheduler() {
      return scheduler;
    }

    @Override
    public dev.holarki.core.Metrics metrics() {
      return null;
    }

    @Override
    public dev.holarki.api.Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}
  }

  private static final class ImmediateScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class InMemoryAttributes implements dev.holarki.api.Attributes {
    private final ConcurrentMap<UUID, ConcurrentMap<String, String>> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(UUID owner, String key) {
      if (owner == null || key == null) {
        return Optional.empty();
      }
      ConcurrentMap<String, String> attrs = values.get(owner);
      if (attrs == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(attrs.get(key));
    }

    @Override
    public void put(UUID owner, String key, String jsonValue, long nowS) {
      values.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>()).put(key, jsonValue);
    }

    @Override
    public void remove(UUID owner, String key) {
      if (owner == null || key == null) {
        return;
      }
      values.computeIfPresent(
          owner,
          (ignored, existing) -> {
            existing.remove(key);
            return existing.isEmpty() ? null : existing;
          });
    }
  }

  private static Config configWithGeoIpDatabase(Path dbPath) throws Exception {
    Config.Db dbConfig =
        new Config.Db(
            "127.0.0.1",
            3306,
            "holarki",
            "user",
            "password",
            false,
            true,
            new Config.Pool(2, 1, 1_000L, 5_000L, 30_000L, 1));
    Config.Runtime runtime = new Config.Runtime(5);
    Config.Time time = new Config.Time(new Config.Display(ZoneId.of("UTC"), true, true));
    Config.I18n i18n =
        new Config.I18n(
            Locale.forLanguageTag("en-US"), List.of("en_US"), Locale.forLanguageTag("en-US"));
    Config.Ledger ledger = new Config.Ledger(false, 0, new Config.JsonlMirror(false, ""));
    Config.Backup backup =
        new Config.Backup(
            false,
            "0 0 0 * * *",
            dbPath.toString(),
            Config.OnMissed.RUN_AT_NEXT_STARTUP,
            false,
            new Config.Prune(0, 0));
    Config.Cleanup cleanup =
        new Config.Cleanup(new Config.IdempotencySweep(false, "0 0 0 * * *", 0, 1));
    Config.Jobs jobs = new Config.Jobs(backup, cleanup);
    Config.SchedulerModule scheduler = new Config.SchedulerModule(false, jobs);
    Config.TimezoneModule timezone =
        new Config.TimezoneModule(true, new Config.AutoDetect(true, dbPath.toString()));
    Config.Modules modules = new Config.Modules(ledger, scheduler, timezone);
    Config.Log log = new Config.Log(false, 250L, "INFO");
    java.lang.reflect.Constructor<Config> ctor =
        Config.class.getDeclaredConstructor(
            Config.Db.class,
            Config.Runtime.class,
            Config.Time.class,
            Config.I18n.class,
            Config.Modules.class,
            Config.Log.class);
    ctor.setAccessible(true);
    return ctor.newInstance(dbConfig, runtime, time, i18n, modules, log);
  }
}
