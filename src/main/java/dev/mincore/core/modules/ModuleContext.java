/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.api.Ledger;
import dev.mincore.core.Config;
import dev.mincore.core.Services;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Execution context handed to {@link MinCoreModule} instances during lifecycle events. */
public final class ModuleContext {
  private final Config config;
  private final Services services;
  private final Consumer<Ledger> ledgerPublisher;
  private final Predicate<String> moduleActive;

  ModuleContext(
      Config config,
      Services services,
      Consumer<Ledger> ledgerPublisher,
      Predicate<String> moduleActive) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    this.ledgerPublisher = Objects.requireNonNull(ledgerPublisher, "ledgerPublisher");
    this.moduleActive = Objects.requireNonNull(moduleActive, "moduleActive");
  }

  /** Returns the runtime configuration. */
  public Config config() {
    return config;
  }

  /** Provides access to the shared service container. */
  public Services services() {
    return services;
  }

  /** Publishes the ledger singleton when the ledger module activates. */
  public void publishLedger(Ledger ledger) {
    ledgerPublisher.accept(ledger);
  }

  /** Whether another module is currently active. */
  public boolean isModuleActive(String moduleId) {
    return moduleActive.test(moduleId);
  }

}
