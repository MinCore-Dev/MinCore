/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.api.Ledger;
import dev.mincore.modules.ledger.LedgerService;

/** Optional ledger subsystem module. */
public final class LedgerModule implements MinCoreModule {
  public static final String ID = "ledger";

  private LedgerService ledger;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) throws Exception {
    var services = context.services();
    ledger =
        LedgerService.install(
            services.database(),
            services.events(),
            services.scheduler(),
            services.metrics(),
            context.config().ledger());
    context.publishLedger(ledger);
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
