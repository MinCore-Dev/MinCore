/* Holarki © 2025 — MIT */
package dev.holarki.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.holarki.api.Attributes;
import dev.holarki.api.Players;
import dev.holarki.api.Playtime;
import dev.holarki.api.Wallets;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import java.time.Duration;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires together all service implementations and manages shared resources (Hikari pool, scheduler).
 */
public final class CoreServices implements Services, java.io.Closeable {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private final HikariDataSource pool;
  private final EventBus events;
  private final Players players;
  private final Wallets wallets;
  private final Attributes attributes;
  private final ModuleDatabaseImpl moduleDatabase;
  private final ScheduledExecutorService scheduler;
  private final Playtime playtime;
  private final DbHealth dbHealth;
  private final Metrics metrics;

  private CoreServices(
      HikariDataSource pool,
      EventBus events,
      Players players,
      Wallets wallets,
      Attributes attributes,
      ModuleDatabaseImpl moduleDatabase,
      ScheduledExecutorService scheduler,
      Playtime playtime,
      DbHealth dbHealth,
      Metrics metrics) {
    this.pool = pool;
    this.events = events;
    this.players = players;
    this.wallets = wallets;
    this.attributes = attributes;
    this.moduleDatabase = moduleDatabase;
    this.scheduler = scheduler;
    this.playtime = playtime;
    this.dbHealth = dbHealth;
    this.metrics = metrics;
  }

  /**
   * Starts core services using the provided configuration.
   *
   * @param cfg runtime configuration
   * @return service container
   */
  public static Services start(Config cfg) {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(cfg.db().jdbcUrl());
    hc.setUsername(cfg.db().user());
    hc.setPassword(cfg.db().password());
    hc.setMaximumPoolSize(cfg.db().pool().maxPoolSize());
    hc.setMinimumIdle(Math.min(cfg.db().pool().minimumIdle(), cfg.db().pool().maxPoolSize()));
    hc.setConnectionTimeout(cfg.db().pool().connectionTimeoutMs());
    hc.setIdleTimeout(cfg.db().pool().idleTimeoutMs());
    hc.setMaxLifetime(cfg.db().pool().maxLifetimeMs());
    hc.setAutoCommit(true);
    hc.setPoolName("holarki-hikari");
    hc.setInitializationFailTimeout(-1);
    hc.setConnectionInitSql(cfg.db().forceUtc() ? "SET time_zone = '+00:00'" : null);

    if (!cfg.db().tlsEnabled() && !isLocalHost(cfg.db().host())) {
      LOG.warn(
          "(holarki) code={} op={} message={}",
          "DB_TLS_DISABLED",
          "config",
          "TLS is disabled for a non-local database host; enable core.db.tls.enabled for security");
    }
    if ("change-me".equals(cfg.db().password())) {
      LOG.warn(
          "(holarki) code={} op={} message={}",
          "DB_PASSWORD_DEFAULT",
          "config",
          "Database password is still set to the default 'change-me'; update before production use");
    }

    HikariDataSource ds = null;
    RuntimeException last = null;
    boolean bootstrapped = false;
    for (int attempt = 1; attempt <= Math.max(1, cfg.db().pool().startupAttempts()); attempt++) {
      try {
        ds = new HikariDataSource(hc);
        break;
      } catch (RuntimeException ex) {
        last = ex;
        SQLException sql = findSqlException(ex);
        if (!bootstrapped && DbBootstrap.isUnknownDatabase(sql)) {
          LOG.warn("(holarki) database missing; attempting bootstrap");
          try {
            DbBootstrap.ensureDatabaseExists(
                cfg.db().jdbcUrl(), cfg.db().user(), cfg.db().password());
            bootstrapped = true;
            continue;
          } catch (SQLException bootstrapEx) {
            LOG.warn("(holarki) database bootstrap failed: {}", bootstrapEx.getMessage());
          }
        }
        LOG.warn(
            "(holarki) failed to start Hikari (attempt {}/{}): {}",
            attempt,
            cfg.db().pool().startupAttempts(),
            ex.getMessage());
        try {
          Thread.sleep(250L * attempt);
        } catch (InterruptedException ignored) {
        }
      }
    }
    if (ds == null) {
      throw new RuntimeException("Unable to start datasource", last);
    }

    ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "holarki-scheduler");
              t.setDaemon(true);
              return t;
            });

    DbHealth dbHealth = new DbHealth(ds, scheduler, cfg.runtime().reconnectEveryS(), cfg.db());

    EventBus events = new EventBus();
    Metrics metrics = new Metrics();
    ModuleDatabaseImpl moduleDb = new ModuleDatabaseImpl(ds, dbHealth, metrics);
    Players players = new PlayersImpl(ds, events, dbHealth, metrics);
    long retentionDays =
        Math.max(0, cfg.modules().scheduler().jobs().cleanup().idempotencySweep().retentionDays());
    Duration idempotencyTtl = Duration.ofDays(retentionDays);
    Wallets wallets = new WalletsImpl(ds, events, dbHealth, metrics, idempotencyTtl);
    Attributes attrs = new AttributesImpl(ds, dbHealth, metrics);
    Playtime playtime = new PlaytimeImpl();

    return new CoreServices(
        ds, events, players, wallets, attrs, moduleDb, scheduler, playtime, dbHealth, metrics);
  }

  @Override
  public Players players() {
    return players;
  }

  @Override
  public Wallets wallets() {
    return wallets;
  }

  @Override
  public Attributes attributes() {
    return attributes;
  }

  @Override
  public CoreEvents events() {
    return events;
  }

  @Override
  public ModuleDatabase database() {
    return moduleDatabase;
  }

  @Override
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  @Override
  public Metrics metrics() {
    return metrics;
  }

  @Override
  public Playtime playtime() {
    return playtime;
  }

  /** Closes background resources and the connection pool. */
  @Override
  public void shutdown() throws IOException {
    moduleDatabase.close();
    try {
      events.close();
    } catch (Exception e) {
      LOG.debug("(holarki) event bus shutdown issue", e);
    }
    metrics.close();
    scheduler.shutdownNow();
    try {
      playtime.close();
    } catch (Exception e) {
      LOG.debug("(holarki) playtime shutdown issue", e);
    }
    pool.close();
  }

  /** Alias for {@link #shutdown()}. */
  @Override
  public void close() throws IOException {
    shutdown();
  }

  private static boolean isLocalHost(String host) {
    if (host == null) {
      return false;
    }
    String normalized = host.trim();
    if (normalized.isEmpty()) {
      return false;
    }
    return normalized.equalsIgnoreCase("localhost")
        || normalized.equals("127.0.0.1")
        || normalized.equals("0.0.0.0")
        || normalized.equals("::1")
        || normalized.equalsIgnoreCase("[::1]");
  }

  private static SQLException findSqlException(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      if (cursor instanceof SQLException sql) {
        return sql;
      }
      cursor = cursor.getCause();
    }
    return null;
  }
}
