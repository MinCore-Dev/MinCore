/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.commands.PlaytimeCommand;
import dev.mincore.core.Config;
import dev.mincore.api.Playtime;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides playtime tracking and the /playtime command. */
public final class PlaytimeModule implements MinCoreModule {
  public static final String ID = "playtime";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().playtime().enabled()) {
      LOG.info("(mincore) playtime module disabled by configuration");
      return;
    }
    Optional<Playtime> playtime = context.services().playtime();
    if (playtime.isEmpty()) {
      LOG.warn("(mincore) playtime module enabled but service unavailable");
      return;
    }
    Playtime instance = playtime.orElseThrow();
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> instance.onJoin(handler.player.getUuid()));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> instance.onQuit(handler.player.getUuid()));
    PlaytimeCommand.register(context.services());
  }

  @Override
  public void stop(ModuleContext context) {
    // Commands and Fabric events cannot be unregistered; the JVM shutdown will clean up threads.
  }
}
