/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core.modules;

import java.util.Objects;
import java.util.Optional;

/** Result returned by a module after attempting activation. */
public final class ModuleActivation {
  private static final ModuleActivation ACTIVATED = new ModuleActivation(true, null);

  private final boolean activated;
  private final String reason;

  private ModuleActivation(boolean activated, String reason) {
    this.activated = activated;
    this.reason = reason;
  }

  /** Returns a result indicating that the module activated successfully. */
  public static ModuleActivation activated() {
    return ACTIVATED;
  }

  /**
   * Returns a result indicating that the module declined to activate.
   *
   * @param reason human friendly skip reason for diagnostics
   */
  public static ModuleActivation skipped(String reason) {
    return new ModuleActivation(false, Objects.requireNonNull(reason, "reason"));
  }

  /** Whether the module activated successfully. */
  public boolean isActivated() {
    return activated;
  }

  /** Optional human friendly reason explaining why activation was skipped. */
  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }
}
