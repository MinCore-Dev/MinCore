/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.api.Ledger;
import dev.mincore.core.Config;
import dev.mincore.core.LedgerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional ledger subsystem module. */
public final class LedgerModule implements MinCoreModule {
  public static final String ID = "ledger";
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private LedgerImpl ledger;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ModuleContext context) throws Exception {
    Config cfg = context.config();
    if (!cfg.modules().ledger().enabled()) {
      LOG.info("(mincore) ledger module skipped by configuration");
      context.publishLedger(null);
      return;
    }
    ledger = LedgerImpl.install(context.services(), cfg);
    context.publishLedger(ledger);
  }

  @Override
  public void stop(ModuleContext context) throws Exception {
    LedgerImpl local = this.ledger;
    this.ledger = null;
    if (local != null) {
      try {
        local.close();
      } finally {
        context.publishLedger(null);
      }
    } else {
      context.publishLedger(null);
    }
  }
}
