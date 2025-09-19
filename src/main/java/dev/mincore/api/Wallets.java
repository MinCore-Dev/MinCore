/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.UUID;

/**
 * Wallet operations with built-in idempotency helpers.
 *
 * <p>Amounts are in smallest currency units (e.g., cents).
 */
public interface Wallets {
  /**
   * Gets the current balance for a player.
   *
   * @param player player UUID
   * @return balance in units
   */
  long getBalance(UUID player);

  /**
   * Deposits into a player's balance.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason (prefer {@code <=} 64 chars)
   * @return {@code true} on success
   */
  boolean deposit(UUID player, long amount, String reason);

  /**
   * Withdraws from a player's balance; fails if insufficient funds.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @return {@code true} on success
   */
  boolean withdraw(UUID player, long amount, String reason);

  /**
   * Transfers between players; fails if insufficient funds in {@code from}.
   *
   * @param from sender UUID
   * @param to recipient UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @return {@code true} on success
   */
  boolean transfer(UUID from, UUID to, long amount, String reason);

  /**
   * Deposits with an explicit idempotency key.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return {@code true} on success
   */
  boolean deposit(UUID player, long amount, String reason, String idemKey);

  /**
   * Withdraws with an explicit idempotency key; fails if insufficient funds.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return {@code true} on success
   */
  boolean withdraw(UUID player, long amount, String reason, String idemKey);

  /**
   * Transfers with an explicit idempotency key; fails if insufficient funds in {@code from}.
   *
   * @param from sender UUID
   * @param to recipient UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return {@code true} on success
   */
  boolean transfer(UUID from, UUID to, long amount, String reason, String idemKey);
}
