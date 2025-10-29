/* Holarki © 2025 — MIT */
package dev.holarki.core.modules;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Central registry for runtime modules. */
public final class ModuleRegistry {
  private static final Map<String, ModuleFactory> FACTORIES =
      Map.of(
          LedgerModule.ID, LedgerModule::new,
          SchedulerModule.ID, SchedulerModule::new,
          TimezoneModule.ID, TimezoneModule::new,
          TimezoneAutoModule.ID, TimezoneAutoModule::new);

  private ModuleRegistry() {}

  /** Returns whether the registry knows about the identifier. */
  public static boolean has(String id) {
    return FACTORIES.containsKey(id);
  }

  /** Creates a new module instance for the identifier. */
  public static Optional<HolarkiModule> create(String id) {
    ModuleFactory factory = FACTORIES.get(id);
    return factory != null ? Optional.of(factory.create()) : Optional.empty();
  }

  /** Returns all registered identifiers. */
  public static Set<String> ids() {
    return FACTORIES.keySet();
  }
}
