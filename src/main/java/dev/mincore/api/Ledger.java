/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.UUID;

/**
 * Public, minimal facade for writing ledger entries from add-ons.
 *
 * <p>The core also logs its own economy mutations (via {@code BalanceChangedEvent}) automatically.
 * Add-ons may call {@link #log(String, String, UUID, UUID, long, String, boolean, String, String,
 * String, String)} to record their own domain events (shop orders, taxes, rewards, etc.).
 *
 * <h2>Stability</h2>
 *
 * This interface is part of the public API surface. It may add new optional parameters in future
 * minor versions. Contract remains stable w.r.t. current methods.
 */
public interface Ledger {

  /**
   * Append a ledger entry.
   *
   * @param addonId add-on identifier, e.g. {@code "shop"} (required, {@literal <=} 64)
   * @param op short operation name, e.g. {@code "buy"}, {@code "refund"} (required, {@literal <=}
   *     32)
   * @param from payer UUID or {@code null} if none
   * @param to payee UUID or {@code null} if none
   * @param amount signed amount in minor units (positive credits, negative debits), or 0 for
   *     non-money events
   * @param reason short, human-friendly reason ({@literal <=} 64). ASCII recommended.
   * @param ok true if the op succeeded and took effect
   * @param code optional canonical outcome code (e.g. {@code INSUFFICIENT_FUNDS}), may be {@code
   *     null}
   * @param idemScope optional idempotency scope, e.g. {@code "shop:order"}, may be {@code null}
   * @param idemKey optional idempotency key to hash (client-supplied token). Hashing/NULL handled
   *     by core
   * @param extraJson optional additional JSON payload (free-form, small). May be {@code null}
   */
  void log(
      String addonId,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code,
      String idemScope,
      String idemKey,
      String extraJson);
}
