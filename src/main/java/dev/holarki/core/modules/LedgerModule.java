/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import dev.holarki.api.Ledger;
import dev.holarki.modules.ledger.LedgerAdminCommands;
import dev.holarki.modules.ledger.LedgerService;

/** Optional ledger subsystem module. */
public final class LedgerModule implements HolarkiModule {
  public static final String ID = "ledger";

  private LedgerService ledger;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public ModuleActivation start(ModuleContext context) throws Exception {
    var services = context.services();
    var config = context.config().ledger();
    ledger =
        LedgerService.install(
            services.database(),
            services.events(),
            services.scheduler(),
            services.metrics(),
            config);
    context.publishLedger(ledger);
    if (!config.enabled()) {
      return ModuleActivation.skipped("ledger module disabled by configuration");
    }
    LedgerAdminCommands.register(context);
    return ModuleActivation.activated();
  }

  @Override
  public void stop(ModuleContext context) throws Exception {
    LedgerService local = this.ledger;
    this.ledger = null;
    try {
      if (local != null) {
        local.close();
      }
    } finally {
      context.publishLedger(null);
    }
  }
}
