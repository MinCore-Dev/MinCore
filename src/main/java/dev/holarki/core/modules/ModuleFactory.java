/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

/** Functional factory used by the {@link ModuleRegistry}. */
@FunctionalInterface
interface ModuleFactory {
  HolarkiModule create();
}
