/* MinCore © 2025 — MIT */
package dev.mincore.api;

import dev.mincore.api.events.CoreEvents;
import dev.mincore.api.storage.ExtensionDatabase;

/**
 * Static accessors for MinCore singletons (players, wallets, attributes, events, database, ledger).
 *
 * <p><strong>Lifecycle</strong>
 *
 * <ol>
 *   <li>The core constructs its {@code Services} container and calls {@link
 *       #bootstrap(dev.mincore.core.Services)} exactly once.
 *   <li>After migrations, core constructs the {@link Ledger} implementation and calls {@link
 *       #publishLedger(Ledger)}.
 * </ol>
 *
 * <p>Add-ons should treat these as read-only. Core is responsible for wiring.
 */
public final class MinCoreApi {
  private static dev.mincore.core.Services services;
  private static Ledger ledger;

  private MinCoreApi() {}

  /**
   * Wire core services into the static API. Call once during mod initialization.
   *
   * @param s service container that provides players, wallets, attributes, events, and database
   * @throws IllegalStateException if called more than once
   */
  public static void bootstrap(dev.mincore.core.Services s) {
    if (services != null) {
      throw new IllegalStateException("MinCoreApi already bootstrapped");
    }
    services = s;
  }

  /**
   * Publish the ledger singleton after the database is ready and migrations have applied.
   *
   * @param l initialized ledger implementation (may be {@code null} if disabled)
   */
  public static void publishLedger(Ledger l) {
    ledger = l;
  }

  /**
   * Gets the player directory service.
   *
   * @return player directory singleton
   */
  public static dev.mincore.api.Players players() {
    return services.players();
  }

  /**
   * Gets the wallet service for deposits, withdrawals, and transfers.
   *
   * @return wallet operations singleton
   */
  public static dev.mincore.api.Wallets wallets() {
    return services.wallets();
  }

  /**
   * Gets the per-player JSON attributes service.
   *
   * @return per-player JSON attributes singleton
   */
  public static dev.mincore.api.Attributes attributes() {
    return services.attributes();
  }

  /**
   * Gets the core event bus facade.
   *
   * @return event bus facade
   */
  public static CoreEvents events() {
    return services.events();
  }

  /**
   * Gets the shared database helpers exposed to add-ons.
   *
   * @return shared database helpers for add-ons
   */
  public static ExtensionDatabase database() {
    return services.database();
  }

  /**
   * Gets the published ledger singleton.
   *
   * @return the ledger instance, or {@code null} if the ledger is disabled in config or not yet
   *     published by core
   */
  public static Ledger ledger() {
    return ledger;
  }
}
