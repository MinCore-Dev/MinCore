/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.core.Config;
import dev.mincore.core.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules background jobs such as backups and cleanups. */
public final class SchedulerModule implements MinCoreModule {
  public static final String ID = "scheduler";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

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
    Scheduler.install(context.services(), cfg);
  }

  @Override
  public void stop(ModuleContext context) {
    Scheduler.shutdown();
  }
}
