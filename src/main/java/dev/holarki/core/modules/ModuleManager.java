/* Holarki © 2025 — MIT */
package dev.holarki.core.modules;

import dev.holarki.api.HolarkiApi;
import dev.holarki.api.Ledger;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates module lifecycle and exposes active module state. Modules are started in the order
 * they are requested and stopped in reverse.
 */
public final class ModuleManager implements AutoCloseable, ModuleStateView {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private final Config config;
  private final Services services;
  private final Map<String, HolarkiModule> modules = new HashMap<>();
  private final List<HolarkiModule> startOrder = new ArrayList<>();
  private final LinkedHashSet<String> active = new LinkedHashSet<>();
  private final Map<Class<?>, Object> services = new HashMap<>();
  private final Map<String, Set<Class<?>>> moduleServices = new HashMap<>();
  private final ModuleContext context;
  private boolean started;

  public ModuleManager(Config config, Services services) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    Predicate<String> predicate = this::isActive;
    this.context =
        new ModuleContext(config, services, this::publishLedger, predicate, this::publishService);
  }

  /** Starts the requested module identifiers. */
  public synchronized void start(Set<String> requested) {
    if (started) {
      throw new IllegalStateException("modules already started");
    }
    Objects.requireNonNull(requested, "requested");

    Deque<HolarkiModule> startedModules = new ArrayDeque<>();
    try {
      for (String id : requested) {
        HolarkiModule module = modules.computeIfAbsent(id, this::newModuleInstance);
        module.start(context);
        active.add(id);
        startOrder.add(module);
        startedModules.push(module);
        LOG.info("(holarki) module '{}' started", id);
      }
      started = true;
    } catch (Exception e) {
      LOG.warn("(holarki) module boot failure: {}", e.getMessage(), e);
      while (!startedModules.isEmpty()) {
        HolarkiModule module = startedModules.pop();
        try {
          module.stop(context);
        } catch (Exception suppressed) {
          LOG.debug("(holarki) module cleanup issue: {}", suppressed.getMessage(), suppressed);
        }
        clearServices(module.id());
      }
      active.clear();
      startOrder.clear();
      throw new RuntimeException("Failed to start modules", e);
    }
  }

  private void publishLedger(Ledger ledger) {
    HolarkiApi.publishLedger(ledger);
  }

  private HolarkiModule newModuleInstance(String id) {
    return ModuleRegistry
        .create(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown module id: " + id));
  }

  @Override
  public synchronized void close() {
    List<HolarkiModule> shutdownOrder = new ArrayList<>(startOrder);
    Collections.reverse(shutdownOrder);
    for (HolarkiModule module : shutdownOrder) {
      try {
        module.stop(context);
      } catch (Exception e) {
        LOG.debug("(holarki) module '{}' shutdown issue: {}", module.id(), e.getMessage(), e);
      }
      active.remove(module.id());
      clearServices(module.id());
    }
    startOrder.clear();
    started = false;
    HolarkiApi.publishLedger(null);
  }

  @Override
  public synchronized boolean isActive(String moduleId) {
    return active.contains(moduleId);
  }

  @Override
  public synchronized Set<String> activeModules() {
    return Set.copyOf(active);
  }

  @Override
  public synchronized <T> Optional<T> service(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(type.cast(services.get(type)));
  }

  private synchronized void publishService(String moduleId, Class<?> type, Object service) {
    Objects.requireNonNull(moduleId, "moduleId");
    Objects.requireNonNull(type, "type");
    if (service == null) {
      services.remove(type);
      Set<Class<?>> published = moduleServices.get(moduleId);
      if (published != null) {
        published.remove(type);
        if (published.isEmpty()) {
          moduleServices.remove(moduleId);
        }
      }
      return;
    }
    services.put(type, service);
    moduleServices.computeIfAbsent(moduleId, id -> new LinkedHashSet<>()).add(type);
  }

  private synchronized void clearServices(String moduleId) {
    Set<Class<?>> published = moduleServices.remove(moduleId);
    if (published != null) {
      for (Class<?> type : published) {
        services.remove(type);
      }
    }
  }
}
