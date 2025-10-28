/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.commands.TimezoneCommand;
import dev.mincore.core.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides the /timezone command hierarchy. */
public final class TimezoneModule implements MinCoreModule {
  public static final String ID = "timezone";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().timezone().enabled()) {
      LOG.info("(mincore) timezone module disabled by configuration");
      return;
    }
    TimezoneCommand.register(context.services());
  }

  @Override
  public void stop(ModuleContext context) {
    // Command registrations cannot be unregistered at runtime; nothing to do.
  }
}
