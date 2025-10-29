/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api;

/**
 * Canonical error/result codes produced by Holarki subsystems.
 *
 * <p>These codes map 1:1 with the catalogue defined in the master specification and are used for
 * ledger rows, admin feedback, and localization keys. Modules or the core surface them to players
 * and operators to explain why an operation failed.
 */
public enum ErrorCode {
  /** Wallet balance would become negative. */
  INSUFFICIENT_FUNDS,

  /** Amount value failed validation (e.g., negative or NaN). */
  INVALID_AMOUNT,

  /** Referenced player UUID or name does not exist. */
  UNKNOWN_PLAYER,

  /** Request was replayed with identical payload and treated as success. */
  IDEMPOTENCY_REPLAY,

  /** Request reused a key with a different payload (poisoned idempotency). */
  IDEMPOTENCY_MISMATCH,

  /** Exhausted retry budget on a deadlock/timeout protected block. */
  DEADLOCK_RETRY_EXHAUSTED,

  /** Database connection pool lost connectivity to the server. */
  CONNECTION_LOST,

  /** Core entered degraded mode and refuses mutations until recovery. */
  DEGRADED_MODE,

  /** Migrations are locked by another node. */
  MIGRATION_LOCKED,

  /** Name lookup returned multiple matches. */
  NAME_AMBIGUOUS,

  /** Invalid timezone identifier supplied. */
  INVALID_TZ,

  /** Invalid clock format supplied. */
  INVALID_CLOCK,

  /** Personal timezone overrides disabled by config. */
  OVERRIDES_DISABLED;
}
