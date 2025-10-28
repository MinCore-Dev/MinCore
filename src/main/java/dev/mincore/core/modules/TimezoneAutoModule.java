/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.core.Config;
import dev.mincore.util.TimezoneAutoDetector;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional timezone auto-detection backed by a local GeoIP database. */
public final class TimezoneAutoModule implements MinCoreModule {
  public static final String ID = "timezone.auto";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private TimezoneAutoDetector detector;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public java.util.Set<String> requires() {
    return java.util.Set.of(TimezoneModule.ID);
  }

  @Override
  public void start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().timezone().enabled()) {
      LOG.info("(mincore) timezone.auto skipped because timezone module is disabled");
      return;
    }
    detector = TimezoneAutoDetector.create(cfg).orElse(null);
    if (detector == null) {
      LOG.info("(mincore) timezone auto-detect not enabled or unavailable");
      return;
    }
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) ->
            scheduleAutoDetect(context, handler.player.getUuid(), handler.player.getIp()));
  }

  private void scheduleAutoDetect(ModuleContext context, UUID uuid, String remoteAddress) {
    if (detector == null) {
      return;
    }
    detector.scheduleDetect(context.services(), uuid, remoteAddress);
  }

  @Override
  public void stop(ModuleContext context) throws Exception {
    if (detector != null) {
      detector.close();
      detector = null;
    }
  }
}
