/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import java.util.Optional;
import java.util.Set;

/**
 * Read-only view of active modules. Exposed so command registration can adapt to enabled
 * subsystems.
 */
public interface ModuleStateView {
  /** Whether the module identifier is currently active. */
  boolean isActive(String moduleId);

  /** Returns the set of active module identifiers. */
  Set<String> activeModules();

  /** Returns an optional module-published service for the requested type. */
  <T> Optional<T> service(Class<T> type);
}
