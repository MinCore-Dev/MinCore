/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

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
}
