/* MinCore © 2025 — MIT */
package dev.mincore.core.modules;

import java.util.Set;

/**
 * Defines a MinCore runtime module. Modules encapsulate optional subsystems such as the ledger,
 * timezone tooling or scheduled jobs. Implementations should be stateless aside from the managed
 * resources they control and must be safe to start/stop exactly once per server lifecycle.
 */
public interface MinCoreModule {
  /**
   * Unique stable identifier for this module. Identifiers are lower-case and dot separated (for
   * example {@code "ledger"} or {@code "timezone.auto"}).
   *
   * @return module identifier
   */
  String id();

  /**
   * Hard dependencies that must be active before this module can start. Missing dependencies cause
   * startup to fail fast.
   *
   * @return required dependency identifiers
   */
  default Set<String> requires() {
    return Set.of();
  }

  /**
   * Optional dependencies that, when present, may be consulted by the module. Missing optional
   * dependencies do not block startup but should be checked through the {@link ModuleContext}.
   *
   * @return optional dependency identifiers
   */
  default Set<String> optionalDependencies() {
    return Set.of();
  }

  /**
   * Starts the module. Implementations should perform registration (commands, listeners) and wire
   * any subsystem state here.
   *
   * @param context module execution context
   * @throws Exception if startup fails; the module manager will abort boot and unwind already
   *     started modules
   */
  void start(ModuleContext context) throws Exception;

  /**
   * Stops the module, releasing resources and unregistering listeners. This is invoked during
   * server shutdown in reverse start order.
   *
   * @param context module execution context
   * @throws Exception if cleanup fails
   */
  void stop(ModuleContext context) throws Exception;
}
