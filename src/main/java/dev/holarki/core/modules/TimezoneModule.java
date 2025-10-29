/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import dev.holarki.commands.TimezoneCommand;
import dev.holarki.core.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides the /timezone command hierarchy. */
public final class TimezoneModule implements HolarkiModule {
  public static final String ID = "timezone";
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public ModuleActivation start(ModuleContext context) {
    Config cfg = context.config();
    if (!cfg.modules().timezone().enabled()) {
      LOG.info("(holarki) timezone module disabled by configuration");
      return ModuleActivation.skipped("timezone module disabled by configuration");
    }
    TimezoneCommand.register(context.services());
    return ModuleActivation.activated();
  }

  @Override
  public void stop(ModuleContext context) {
    // Command registrations cannot be unregistered at runtime; nothing to do.
  }
}
