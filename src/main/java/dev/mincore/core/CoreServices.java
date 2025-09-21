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
  private final ExtensionDatabase extensionDb;
  private final ScheduledExecutorService scheduler;
  private final Playtime playtime;

  private CoreServices(
      HikariDataSource pool,
      EventBus events,
      Players players,
      Wallets wallets,
      Attributes attributes,
      ExtensionDatabase extensionDb,
      ScheduledExecutorService scheduler,
      Playtime playtime) {
    this.pool = pool;
    this.events = events;
    this.players = players;
    this.wallets = wallets;
    this.attributes = attributes;
    this.extensionDb = extensionDb;
    this.scheduler = scheduler;
    this.playtime = playtime;
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

    EventBus events = new EventBus();
    ExtensionDbImpl ext = new ExtensionDbImpl(ds);
    Players players = new PlayersImpl(ds, events);
    Wallets wallets = new WalletsImpl(ds, events);
    Attributes attrs = new AttributesImpl(ds);
    PlaytimeImpl play = new PlaytimeImpl();

    // Track playtime via Fabric connection events
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> play.onJoin(handler.player.getUuid()));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> play.onQuit(handler.player.getUuid()));

    ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "mincore-scheduler");
              t.setDaemon(true);
              return t;
            });

    return new CoreServices(ds, events, players, wallets, attrs, ext, scheduler, play);
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
