/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import dev.mincore.api.MinCoreApi;
import dev.mincore.api.Ledger;
import dev.mincore.core.Config;
import dev.mincore.core.Services;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates module lifecycle and exposes active module state. Modules are started in the order
 * they are requested and stopped in reverse.
 */
public final class ModuleManager implements AutoCloseable, ModuleStateView {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private final Config config;
  private final Services services;
  private final Map<String, MinCoreModule> modules = new HashMap<>();
  private final List<MinCoreModule> startOrder = new ArrayList<>();
  private final LinkedHashSet<String> active = new LinkedHashSet<>();
  private final ModuleContext context;
  private boolean started;

  public ModuleManager(Config config, Services services) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    Predicate<String> predicate = this::isActive;
    this.context = new ModuleContext(config, services, this::publishLedger, predicate);
  }

  /** Starts the requested module identifiers. */
  public synchronized void start(Set<String> requested) {
    if (started) {
      throw new IllegalStateException("modules already started");
    }
    Objects.requireNonNull(requested, "requested");

    Deque<MinCoreModule> startedModules = new ArrayDeque<>();
    try {
      for (String id : requested) {
        MinCoreModule module = modules.computeIfAbsent(id, this::newModuleInstance);
        module.start(context);
        active.add(id);
        startOrder.add(module);
        startedModules.push(module);
        LOG.info("(mincore) module '{}' started", id);
      }
      started = true;
    } catch (Exception e) {
      LOG.warn("(mincore) module boot failure: {}", e.getMessage(), e);
      while (!startedModules.isEmpty()) {
        MinCoreModule module = startedModules.pop();
        try {
          module.stop(context);
        } catch (Exception suppressed) {
          LOG.debug("(mincore) module cleanup issue: {}", suppressed.getMessage(), suppressed);
        }
      }
      active.clear();
      startOrder.clear();
      throw new RuntimeException("Failed to start modules", e);
    }
  }

  private void publishLedger(Ledger ledger) {
    MinCoreApi.publishLedger(ledger);
  }

  private MinCoreModule newModuleInstance(String id) {
    return ModuleRegistry
        .create(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown module id: " + id));
  }

  @Override
  public synchronized void close() {
    List<MinCoreModule> shutdownOrder = new ArrayList<>(startOrder);
    Collections.reverse(shutdownOrder);
    for (MinCoreModule module : shutdownOrder) {
      try {
        module.stop(context);
      } catch (Exception e) {
        LOG.debug("(mincore) module '{}' shutdown issue: {}", module.id(), e.getMessage(), e);
      }
      active.remove(module.id());
    }
    startOrder.clear();
    started = false;
    MinCoreApi.publishLedger(null);
  }

  @Override
  public synchronized boolean isActive(String moduleId) {
    return active.contains(moduleId);
  }

  @Override
  public synchronized Set<String> activeModules() {
    return Set.copyOf(active);
  }
}
