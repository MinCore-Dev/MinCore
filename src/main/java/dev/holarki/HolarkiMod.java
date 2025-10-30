/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki;

import dev.holarki.api.HolarkiApi;
import dev.holarki.commands.AdminCommands;
import dev.holarki.commands.PlaytimeCommand;
import dev.holarki.core.Config;
import dev.holarki.core.CoreServices;
import dev.holarki.core.LocaleManager;
import dev.holarki.core.Migrations;
import dev.holarki.core.SchemaVerifier;
import dev.holarki.core.Services;
import dev.holarki.core.modules.LedgerModule;
import dev.holarki.core.modules.ModuleManager;
import dev.holarki.core.modules.SchedulerModule;
import dev.holarki.core.modules.TimezoneAutoModule;
import dev.holarki.core.modules.TimezoneModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holarki mod entrypoint.
 *
 * <p>Boot sequence:
 *
 * <ol>
 *   <li>Load MariaDB driver from disk (keeps core jar tiny)
 *   <li>Load config (writes default JSON5 if missing)
 *   <li>Start services (Hikari pool, event bus, implementations)
 *   <li>Apply DDL migrations (incl. core_ledger if enabled)
 *   <li>Expose services through {@link dev.holarki.api.HolarkiApi}
 *   <li>Install ledger + admin commands
 *   <li>Register join + shutdown hooks
 * </ol>
 */
public final class HolarkiMod implements ModInitializer {
  /** Fabric mod id. */
  public static final String MOD_ID = "holarki";

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
  private static Config CONFIG;
  private static ModuleManager MODULES;

  /** Public no-arg constructor for Fabric. */
  public HolarkiMod() {}

  /** Initializes Holarki when Fabric loads the mod. */
  @Override
  public void onInitialize() {
    LOG.info("(holarki) booting Holarki 1.0.0");
    // 1) Ensure MariaDB driver is present before the pool tries to connect.
    dev.holarki.jdbc.DriverLoader.tryLoadMariaDbDriver();

    // 2) Config + services
    Path cfgPath = Path.of("config", "holarki.json5");
    Config cfg = Config.loadOrWriteDefault(cfgPath);
    LocaleManager.initialize(cfg);
    CONFIG = cfg;
    Services services = CoreServices.start(cfg);
    MODULES = new ModuleManager(cfg, services);

    // 3) DDL (idempotent; safe to run every boot)
    Migrations.apply(services);
    SchemaVerifier.verify(services);

    // 4) Publish services to API
    HolarkiApi.bootstrap(services);

    // 5) Modules (optional subsystems)
    var requested = new LinkedHashSet<String>();
    // Ledger module always starts so the API exposes a no-op handle when persistence is disabled.
    requested.add(LedgerModule.ID);
    if (cfg.modules().timezone().enabled()) {
      requested.add(TimezoneModule.ID);
    }
    if (cfg.modules().timezone().autoDetect().enabled() && cfg.time().display().autoDetect()) {
      requested.add(TimezoneAutoModule.ID);
    }
    if (cfg.modules().scheduler().enabled()) {
      requested.add(SchedulerModule.ID);
    }
    MODULES.start(requested);

    PlaytimeCommand.register(services);

    // 6) Ensure player account exists on join
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> {
          var player = handler.player;
          UUID uuid = player.getUuid();
          String name = player.getGameProfile().getName();
          long now = Instant.now().getEpochSecond();

          services.playtime().onJoin(uuid);

          Runnable upsertSeen =
              () -> {
                try {
                  services.players().upsertSeen(uuid, name, now);
                } catch (Exception e) {
                  LOG.error(
                      "(holarki) failed to update player seen record (uuid={}, name={})",
                      uuid,
                      name,
                      e);
                }
              };

          var scheduler = services.scheduler();
          if (scheduler.isShutdown()) {
            LOG.debug("(holarki) skip player seen upsert for {} (scheduler stopping)", uuid);
            return;
          }

          try {
            scheduler.execute(upsertSeen);
          } catch (RejectedExecutionException ex) {
            LOG.debug(
                "(holarki) scheduler rejected player seen upsert for {} (stopping)", uuid, ex);
          }
        });

    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> services.playtime().onQuit(handler.player.getUuid()));

    // 7) Admin commands (db diag + ledger peek)
    AdminCommands.register(services, MODULES);

    // 8) Graceful shutdown
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          try {
            if (MODULES != null) {
              MODULES.close();
              MODULES = null;
            }
            services.shutdown();
          } catch (Exception e) {
            LOG.warn("(holarki) shutdown error", e);
          }
        });

    LOG.info("(holarki) initialized");
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
