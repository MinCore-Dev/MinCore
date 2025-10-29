/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

/**
 * Defines a Holarki runtime module. Modules encapsulate optional subsystems such as the ledger,
 * timezone tooling or scheduled jobs. Implementations should be stateless aside from the managed
 * resources they control and must be safe to start/stop exactly once per server lifecycle.
 */
public interface HolarkiModule {
  /**
   * Unique stable identifier for this module. Identifiers are lower-case and dot separated (for
   * example {@code "ledger"} or {@code "timezone.auto"}). Primary modules use a single segment,
   * while optional sub-modules append a dotted suffix to the parent module identifier so operators
   * can see the dependency lineage at a glance (for example, {@code "timezone.auto"} depends on
   * {@code "timezone"}). Sub-modules must not introduce cross-coupling with unrelated modules;
   * they are extensions of their parent only.
   *
   * @return module identifier
   */
  String id();

  /**
   * Starts the module. Implementations should perform registration (commands, listeners) and wire
   * any subsystem state here. Modules that decline activation should return
   * {@link ModuleActivation#skipped(String)} with a human-readable reason so operators understand
   * why the module is inactive.
   *
   * @param context module execution context
   * @return activation result signalling whether the module is active
   * @throws Exception if startup fails; the module manager will abort boot and unwind already
   *     started modules
   */
  ModuleActivation start(ModuleContext context) throws Exception;

  /**
   * Stops the module, releasing resources and unregistering listeners. This is invoked during
   * server shutdown in reverse start order.
   *
   * @param context module execution context
   * @throws Exception if cleanup fails
   */
  void stop(ModuleContext context) throws Exception;
}
