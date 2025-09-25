/* MinCore © 2025 — MIT */
package dev.mincore.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.mincore.api.Attributes;
import dev.mincore.api.Players;
import dev.mincore.api.Playtime;
import dev.mincore.api.Wallets;
import dev.mincore.api.events.CoreEvents;
import dev.mincore.api.storage.ExtensionDatabase;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires together all service implementations and manages shared resources (Hikari pool, scheduler).
 */
public final class CoreServices implements Services, java.io.Closeable {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private final HikariDataSource pool;
  private final EventBus events;
  private final Players players;
  private final Wallets wallets;
  private final Attributes attributes;
  private final ExtensionDbImpl extensionDb;
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
      ExtensionDbImpl extensionDb,
      ScheduledExecutorService scheduler,
      Playtime playtime,
      DbHealth dbHealth,
      Metrics metrics) {
    this.pool = pool;
    this.events = events;
    this.players = players;
    this.wallets = wallets;
    this.attributes = attributes;
    this.extensionDb = extensionDb;
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
    hc.setAutoCommit(false);
    hc.setPoolName("mincore-hikari");
    hc.setInitializationFailTimeout(-1);
    hc.setConnectionInitSql(cfg.db().forceUtc() ? "SET time_zone = '+00:00'" : null);

    if (!cfg.db().tlsEnabled() && !isLocalHost(cfg.db().host())) {
      LOG.warn(
          "(mincore) code={} op={} message={}",
          "DB_TLS_DISABLED",
          "config",
          "TLS is disabled for a non-local database host; enable core.db.tls.enabled for security");
    }
    if ("change-me".equals(cfg.db().password())) {
      LOG.warn(
          "(mincore) code={} op={} message={}",
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
          LOG.warn("(mincore) database missing; attempting bootstrap");
          try {
            DbBootstrap.ensureDatabaseExists(
                cfg.db().jdbcUrl(), cfg.db().user(), cfg.db().password());
            bootstrapped = true;
            continue;
          } catch (SQLException bootstrapEx) {
            LOG.warn("(mincore) database bootstrap failed: {}", bootstrapEx.getMessage());
          }
        }
        LOG.warn(
            "(mincore) failed to start Hikari (attempt {}/{}): {}",
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
              Thread t = new Thread(r, "mincore-scheduler");
              t.setDaemon(true);
              return t;
            });

    DbHealth dbHealth = new DbHealth(ds, scheduler, cfg.runtime().reconnectEveryS(), cfg.db());

    EventBus events = new EventBus();
    Metrics metrics = new Metrics();
    ExtensionDbImpl ext = new ExtensionDbImpl(ds, dbHealth, metrics);
    Players players = new PlayersImpl(ds, events, dbHealth, metrics);
    Wallets wallets = new WalletsImpl(ds, events, dbHealth, metrics);
    Attributes attrs = new AttributesImpl(ds, dbHealth, metrics);
    PlaytimeImpl play = new PlaytimeImpl();

    // Track playtime via Fabric connection events
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> play.onJoin(handler.player.getUuid()));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> play.onQuit(handler.player.getUuid()));

    return new CoreServices(
        ds, events, players, wallets, attrs, ext, scheduler, play, dbHealth, metrics);
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
  public ExtensionDatabase database() {
    return extensionDb;
  }

  @Override
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  @Override
  public Playtime playtime() {
    return playtime;
  }

  /** Closes background resources and the connection pool. */
  @Override
  public void shutdown() throws IOException {
    extensionDb.close();
    try {
      events.close();
    } catch (Exception e) {
      LOG.debug("(mincore) event bus shutdown issue", e);
    }
    metrics.close();
    scheduler.shutdownNow();
    pool.close();
  }

  /** Alias for {@link #shutdown()}. */
  @Override
  public void close() throws IOException {
    shutdown();
  }

  // === Package-private accessor needed by LedgerImpl.install(...) ===
  HikariDataSource pool() {
    return pool;
  }

  DbHealth dbHealth() {
    return dbHealth;
  }

  Metrics metrics() {
    return metrics;
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
