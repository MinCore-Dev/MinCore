/* MinCore © 2025 — MIT */
package dev.mincore.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Wallet operations with built-in idempotency helpers.
 *
 * <p>Amounts are in smallest currency units (e.g., cents). Every mutation method provides both a
 * legacy boolean-returning variant and an {@link OperationResult} rich result that exposes the
 * {@link ErrorCode} explaining failures.
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
  default boolean deposit(UUID player, long amount, String reason) {
    return depositResult(player, amount, reason, autoKey()).ok();
  }

  /**
   * Withdraws from a player's balance; fails if insufficient funds.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @return {@code true} on success
   */
  default boolean withdraw(UUID player, long amount, String reason) {
    return withdrawResult(player, amount, reason, autoKey()).ok();
  }

  /**
   * Transfers between players; fails if insufficient funds in {@code from}.
   *
   * @param from sender UUID
   * @param to recipient UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @return {@code true} on success
   */
  default boolean transfer(UUID from, UUID to, long amount, String reason) {
    return transferResult(from, to, amount, reason, autoKey()).ok();
  }

  /**
   * Deposits with an explicit idempotency key.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return {@code true} on success
   */
  default boolean deposit(UUID player, long amount, String reason, String idemKey) {
    return depositResult(player, amount, reason, idemKey).ok();
  }

  /**
   * Withdraws with an explicit idempotency key; fails if insufficient funds.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return {@code true} on success
   */
  default boolean withdraw(UUID player, long amount, String reason, String idemKey) {
    return withdrawResult(player, amount, reason, idemKey).ok();
  }

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
  default boolean transfer(UUID from, UUID to, long amount, String reason, String idemKey) {
    return transferResult(from, to, amount, reason, idemKey).ok();
  }

  /**
   * Deposits into a player's balance and returns a structured result with an {@link ErrorCode}.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return structured outcome describing success/failure
   */
  OperationResult depositResult(UUID player, long amount, String reason, String idemKey);

  /**
   * Withdraws from a player's balance and returns a structured result with an {@link ErrorCode}.
   *
   * @param player player UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return structured outcome describing success/failure
   */
  OperationResult withdrawResult(UUID player, long amount, String reason, String idemKey);

  /**
   * Transfers between players and returns a structured result with an {@link ErrorCode}.
   *
   * @param from sender UUID
   * @param to recipient UUID
   * @param amount amount (must be &gt;= 0)
   * @param reason short reason
   * @param idemKey idempotency key string
   * @return structured outcome describing success/failure
   */
  OperationResult transferResult(UUID from, UUID to, long amount, String reason, String idemKey);

  /** Rich result describing a wallet mutation outcome. */
  record OperationResult(boolean ok, ErrorCode code, String message) {
    /** Success without additional context. */
    public static OperationResult success() {
      return new OperationResult(true, null, null);
    }

    /**
     * Success with an additional semantic {@link ErrorCode}, e.g. {@link
     * ErrorCode#IDEMPOTENCY_REPLAY}.
     */
    public static OperationResult success(ErrorCode code, String message) {
      return new OperationResult(true, Objects.requireNonNull(code, "code"), message);
    }

    /** Failure with a canonical {@link ErrorCode}. */
    public static OperationResult failure(ErrorCode code, String message) {
      return new OperationResult(false, Objects.requireNonNull(code, "code"), message);
    }

    public OperationResult {
      if (!ok && code == null) {
        throw new IllegalArgumentException("failure results require an error code");
      }
    }
  }

  private static String autoKey() {
    return "core:auto:" + UUID.randomUUID();
  }
}
