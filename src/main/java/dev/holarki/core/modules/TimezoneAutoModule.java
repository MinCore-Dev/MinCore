/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import dev.holarki.core.Config;
import dev.holarki.util.TimezoneAutoDetector;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional timezone auto-detection backed by a local GeoIP database. */
public final class TimezoneAutoModule implements HolarkiModule {
  public static final String ID = "timezone.auto";
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private TimezoneAutoDetector detector;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public ModuleActivation start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().timezone().enabled()) {
      LOG.info("(holarki) timezone.auto skipped because timezone module is disabled");
      return ModuleActivation.skipped("timezone module disabled");
    }
    if (!context.isModuleActive(TimezoneModule.ID)) {
      LOG.info("(holarki) timezone.auto skipped because timezone module is inactive");
      return ModuleActivation.skipped("timezone module inactive");
    }
    detector = TimezoneAutoDetector.create(cfg).orElse(null);
    if (detector == null) {
      LOG.info("(holarki) timezone auto-detect not enabled or unavailable");
      return ModuleActivation.skipped("timezone auto-detect unavailable");
    }
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) ->
            scheduleAutoDetect(context, handler.player.getUuid(), handler.player.getIp()));
    return ModuleActivation.activated();
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
