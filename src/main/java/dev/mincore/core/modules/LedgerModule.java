/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.api.Ledger;
import dev.mincore.core.Config;
import dev.mincore.core.LedgerImpl;

/** Optional ledger subsystem module. */
public final class LedgerModule implements MinCoreModule {
  public static final String ID = "ledger";

  private LedgerImpl ledger;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) throws Exception {
    Config cfg = context.config();
    ledger = LedgerImpl.install(context.services(), cfg);
    context.publishLedger(ledger);
  }

  @Override
  public void stop(ModuleContext context) throws Exception {
    LedgerImpl local = this.ledger;
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
