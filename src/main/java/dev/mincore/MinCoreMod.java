/* MinCore © 2025 — MIT */
package dev.mincore;

import dev.mincore.api.MinCoreApi;
import dev.mincore.commands.AdminCommands;
import dev.mincore.commands.PlaytimeCommand;
import dev.mincore.commands.TimezoneCommand;
import dev.mincore.core.Config;
import dev.mincore.core.CoreServices;
import dev.mincore.core.LedgerImpl;
import dev.mincore.core.Migrations;
import dev.mincore.core.Scheduler;
import dev.mincore.core.SchemaVerifier;
import dev.mincore.core.Services;
import dev.mincore.util.TimezoneAutoDetector;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinCore mod entrypoint.
 *
 * <p>Boot sequence:
 *
 * <ol>
 *   <li>Load MariaDB driver from disk (keeps core jar tiny)
 *   <li>Load config (writes default JSON5 if missing)
 *   <li>Start services (Hikari pool, event bus, implementations)
 *   <li>Apply DDL migrations (incl. core_ledger if enabled)
 *   <li>Expose services through {@link dev.mincore.api.MinCoreApi}
 *   <li>Install ledger + admin commands
 *   <li>Register join + shutdown hooks
 * </ol>
 */
public final class MinCoreMod implements ModInitializer {
  /** Fabric mod id. */
  public static final String MOD_ID = "mincore";

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
  private static Config CONFIG;
  private static TimezoneAutoDetector TIMEZONE_AUTO_DETECTOR;

  /** Public no-arg constructor for Fabric. */
  public MinCoreMod() {}

  /** Initializes MinCore when Fabric loads the mod. */
  @Override
  public void onInitialize() {
    LOG.info("(mincore) booting MinCore 1.0.0");
    // 1) Ensure MariaDB driver is present before the pool tries to connect.
    dev.mincore.jdbc.DriverLoader.tryLoadMariaDbDriver();

    // 2) Config + services
    Path cfgPath = Path.of("config", "mincore.json5");
    Config cfg = Config.loadOrWriteDefault(cfgPath);
    CONFIG = cfg;
    Services services = CoreServices.start(cfg);
    TIMEZONE_AUTO_DETECTOR = TimezoneAutoDetector.create(cfg).orElse(null);

    // 3) DDL (idempotent; safe to run every boot)
    Migrations.apply(services);
    SchemaVerifier.verify(services);

    // 4) Publish services to API
    MinCoreApi.bootstrap(services);

    // 5) Ledger (optional per config) then publish to API
    LedgerImpl ledger = LedgerImpl.install(services, cfg);
    MinCoreApi.publishLedger(ledger);

    // 6) Ensure player account exists on join
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> {
          var p = handler.player;
          UUID uuid = p.getUuid();
          String name = p.getGameProfile().getName();
          long now = java.time.Instant.now().getEpochSecond();
          services.players().upsertSeen(uuid, name, now);
          boolean owner =
              server != null && !server.isDedicated() && server.isHost(p.getGameProfile());
          if (TIMEZONE_AUTO_DETECTOR != null && owner) {
            String remote = handler.player.getIp();
            TIMEZONE_AUTO_DETECTOR.scheduleDetect(services, uuid, remote);
          }
        });

    // 7) Admin commands (db diag + ledger peek)
    AdminCommands.register(services);

    // Player-facing commands
    TimezoneCommand.register(services);
    PlaytimeCommand.register(services);

    // 8) Scheduler hooks (backups, sweeps, etc.)
    Scheduler.install(services, cfg);
    // 9) Graceful shutdown
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          try {
            if (TIMEZONE_AUTO_DETECTOR != null) {
              TIMEZONE_AUTO_DETECTOR.close();
            }
            services.shutdown();
          } catch (Exception e) {
            LOG.warn("(mincore) shutdown error", e);
          }
        });

    LOG.info("(mincore) initialized");
  }

  /**
   * Returns the currently loaded runtime configuration.
   *
   * @return active configuration instance
   */
  public static Config config() {
    return CONFIG;
  }
}
