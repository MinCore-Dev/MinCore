/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

/** Functional factory used by the {@link ModuleRegistry}. */
@FunctionalInterface
interface ModuleFactory {
  MinCoreModule create();
}
