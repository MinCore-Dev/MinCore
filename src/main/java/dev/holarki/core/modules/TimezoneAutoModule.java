/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import dev.holarki.core.Config;
import dev.holarki.util.TimezoneAutoDetector;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional timezone auto-detection backed by a local GeoIP database. */
public final class TimezoneAutoModule implements HolarkiModule {
  public static final String ID = "timezone.auto";
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private TimezoneAutoDetector detector;
  private final JoinRegistrar joinRegistrar;
  private ServerJoinListener joinListener;
  private boolean joinRegistered;
  private ModuleContext activeContext;

  public TimezoneAutoModule() {
    this(defaultJoinRegistrar());
  }

  TimezoneAutoModule(JoinRegistrar joinRegistrar) {
    this.joinRegistrar = Objects.requireNonNull(joinRegistrar, "joinRegistrar");
  }

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
    activeContext = context;
    detector = TimezoneAutoDetector.create(cfg).orElse(null);
    if (detector == null) {
      LOG.info("(holarki) timezone auto-detect not enabled or unavailable");
      activeContext = null;
      return ModuleActivation.skipped("timezone auto-detect unavailable");
    }
    if (!joinRegistered) {
      if (joinListener == null) {
        joinListener = (uuid, remoteAddress) -> scheduleAutoDetect(uuid, remoteAddress);
      }
      joinRegistrar.register(joinListener);
      joinRegistered = true;
    }
    return ModuleActivation.activated();
  }

  private void scheduleAutoDetect(UUID uuid, String remoteAddress) {
    ModuleContext context = activeContext;
    if (detector == null || context == null) {
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
    activeContext = null;
  }

  private static JoinRegistrar defaultJoinRegistrar() {
    return listener ->
        ServerPlayConnectionEvents.JOIN.register(
            (handler, sender, server) ->
                listener.onJoin(handler.player.getUuid(), handler.player.getIp()));
  }

  @FunctionalInterface
  interface JoinRegistrar {
    void register(ServerJoinListener listener);
  }

  @FunctionalInterface
  interface ServerJoinListener {
    void onJoin(UUID uuid, String remoteAddress);
  }
}
