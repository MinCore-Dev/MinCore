/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api;

import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;

/**
 * Static accessors for Holarki singletons (players, wallets, attributes, events, database, ledger)
 * consumed by built-in modules and server operators.
 *
 * <p><strong>Lifecycle</strong>
 *
 * <ol>
 *   <li>The core constructs its {@code Services} container and calls {@link
 *       #bootstrap(dev.holarki.core.Services)} exactly once.
 *   <li>After migrations, core constructs the {@link Ledger} implementation and calls {@link
 *       #publishLedger(Ledger)}.
 * </ol>
 *
 * <p>Bundled modules and operator automation should treat these as read-only handles. Core is
 * responsible for wiring.
 */
public final class HolarkiApi {
  private static dev.holarki.core.Services services;
  private static Ledger ledger;

  private HolarkiApi() {}

  /**
   * Wire core services into the static API. Call once during mod initialization.
   *
   * @param s service container that provides players, wallets, attributes, events, and database
   * @throws IllegalStateException if called more than once
   */
  public static void bootstrap(dev.holarki.core.Services s) {
    if (services != null) {
      throw new IllegalStateException("HolarkiApi already bootstrapped");
    }
    services = s;
  }

  /**
   * Publish the ledger singleton after the database is ready and migrations have applied.
   *
   * <p>During normal startup core passes a fully initialized, non-null ledger—even when
   * persistence is disabled—so bundled modules and automation can continue to enqueue entries that
   * will be no-ops.
   * During shutdown (including module stop) core publishes {@code null} to clear the handle and
   * signal that the ledger is no longer available.
   *
   * @param l initialized ledger implementation, or {@code null} when core is clearing the
   *     published handle while shutting down
   */
  public static void publishLedger(Ledger l) {
    ledger = l;
  }

  /**
   * Gets the player directory service.
   *
   * @return player directory singleton
   */
  public static dev.holarki.api.Players players() {
    return services.players();
  }

  /**
   * Gets the wallet service for deposits, withdrawals, and transfers.
   *
   * @return wallet operations singleton
   */
  public static dev.holarki.api.Wallets wallets() {
    return services.wallets();
  }

  /**
   * Gets the per-player JSON attributes service.
   *
   * @return per-player JSON attributes singleton
   */
  public static dev.holarki.api.Attributes attributes() {
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
   * Gets the shared database helpers used by bundled modules and operator automation.
   *
   * @return shared database helpers for modules and automation
   */
  public static ModuleDatabase database() {
    return services.database();
  }

  /**
   * Gets the playtime tracker singleton.
   *
   * @return playtime tracker singleton
   */
  public static dev.holarki.api.Playtime playtime() {
    return services.playtime();
  }

  /**
   * Gets the published ledger singleton.
   *
   * <p>After {@link #bootstrap(dev.holarki.core.Services)} and the initial
   * {@link #publishLedger(Ledger)} call complete, this accessor returns a non-null handle—even when
   * ledger persistence is disabled in configuration. Core republishes {@code null} when the ledger
   * module stops or during shutdown to indicate the handle has been cleared.
   *
   * @return the ledger instance, or {@code null} before bootstrap completes or once shutdown/module
   *     stop begins
   */
  public static Ledger ledger() {
    return ledger;
  }
}
