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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates module lifecycle and exposes active module state. Modules are started in dependency
 * order and stopped in reverse.
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

    List<String> ordered = resolveOrder(requested);
    Deque<MinCoreModule> startedModules = new ArrayDeque<>();
    try {
      for (String id : ordered) {
        MinCoreModule module = modules.get(id);
        if (module == null) {
          throw new IllegalStateException("Module not registered: " + id);
        }
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

  private List<String> resolveOrder(Set<String> requested) {
    Set<String> requestedCopy = new LinkedHashSet<>();
    for (String id : requested) {
      if (!ModuleRegistry.has(id)) {
        throw new IllegalArgumentException("Unknown module id: " + id);
      }
      modules.computeIfAbsent(id, this::newModuleInstance);
      requestedCopy.add(id);
    }

    List<String> order = new ArrayList<>();
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String id : requestedCopy) {
      visit(id, requestedCopy, visiting, visited, order);
    }
    return order;
  }

  private void visit(
      String id,
      Set<String> requested,
      Set<String> visiting,
      Set<String> visited,
      List<String> order) {
    if (visited.contains(id)) {
      return;
    }
    if (!requested.contains(id)) {
      return;
    }
    if (!modules.containsKey(id)) {
      modules.computeIfAbsent(id, this::newModuleInstance);
    }
    if (!visiting.add(id)) {
      throw new IllegalStateException("Module dependency cycle detected at " + id);
    }
    MinCoreModule module = modules.get(id);
    for (String dep : module.requires()) {
      if (!requested.contains(dep)) {
        throw new IllegalStateException(
            "Module '" + id + "' requires missing module '" + dep + "'");
      }
      visit(dep, requested, visiting, visited, order);
    }
    visiting.remove(id);
    visited.add(id);
    if (!order.contains(id)) {
      order.add(id);
    }
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
