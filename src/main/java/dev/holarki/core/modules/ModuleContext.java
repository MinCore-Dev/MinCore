/* Holarki © 2025 — MIT */
package dev.holarki.core.modules;

import dev.holarki.api.Ledger;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Execution context handed to {@link HolarkiModule} instances during lifecycle events. */
public final class ModuleContext {
  private final Config config;
  private final Services services;
  private final Consumer<Ledger> ledgerPublisher;
  private final Predicate<String> moduleActive;
  private final ModuleServicePublisher servicePublisher;

  ModuleContext(
      Config config,
      Services services,
      Consumer<Ledger> ledgerPublisher,
      Predicate<String> moduleActive,
      ModuleServicePublisher servicePublisher) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    this.ledgerPublisher = Objects.requireNonNull(ledgerPublisher, "ledgerPublisher");
    this.moduleActive = Objects.requireNonNull(moduleActive, "moduleActive");
    this.servicePublisher = Objects.requireNonNull(servicePublisher, "servicePublisher");
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

  /** Publishes a module-scoped service for other components to consume. */
  public <T> void publishService(String moduleId, Class<T> type, T service) {
    Objects.requireNonNull(moduleId, "moduleId");
    Objects.requireNonNull(type, "type");
    servicePublisher.publish(moduleId, type, service);
  }

  @FunctionalInterface
  interface ModuleServicePublisher {
    void publish(String moduleId, Class<?> type, Object service);
  }

}
