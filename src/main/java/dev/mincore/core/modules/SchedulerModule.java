/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.core.Config;
import dev.mincore.modules.scheduler.SchedulerEngine;
import dev.mincore.modules.scheduler.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules background jobs such as backups and cleanups. */
public final class SchedulerModule implements MinCoreModule {
  public static final String ID = "scheduler";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private SchedulerEngine engine;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().scheduler().enabled()) {
      LOG.info("(mincore) scheduler module disabled by configuration");
      return;
    }
    SchedulerEngine engine = new SchedulerEngine();
    engine.start(context.services(), cfg);
    this.engine = engine;
    context.publishService(ID, SchedulerService.class, engine);
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
