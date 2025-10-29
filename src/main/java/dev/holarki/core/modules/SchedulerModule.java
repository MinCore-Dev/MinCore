/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import dev.holarki.core.Config;
import dev.holarki.modules.scheduler.SchedulerAdminCommands;
import dev.holarki.modules.scheduler.SchedulerEngine;
import dev.holarki.modules.scheduler.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules background jobs such as backups and cleanups. */
public final class SchedulerModule implements HolarkiModule {
  public static final String ID = "scheduler";
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private SchedulerEngine engine;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public ModuleActivation start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().scheduler().enabled()) {
      LOG.info("(holarki) scheduler module disabled by configuration");
      return ModuleActivation.skipped("scheduler module disabled by configuration");
    }
    SchedulerEngine engine = new SchedulerEngine();
    engine.start(context.services(), cfg);
    this.engine = engine;
    context.publishService(ID, SchedulerService.class, engine);
    SchedulerAdminCommands.register(context, engine);
    return ModuleActivation.activated();
  }

  @Override
  public void stop(ModuleContext context) {
    SchedulerEngine current = this.engine;
    if (current != null) {
      current.stop();
      context.publishService(ID, SchedulerService.class, null);
      this.engine = null;
    }
  }
}
