/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Key-value storage for per-player JSON attributes.
 *
 * <p>Values are stored as JSON (string). The database layer enforces size limits and JSON validity.
 */
public interface Attributes {
  /**
   * Returns the JSON value for {@code key}, if present for {@code owner}.
   *
   * @param owner UUID of attribute owner (player)
   * @param key attribute name
   * @return JSON string if present
   */
  Optional<String> get(UUID owner, String key);

  /**
   * Upserts {@code key} with {@code jsonValue}.
   *
   * @param owner owner UUID
   * @param key attribute name
   * @param jsonValue JSON value (caller provides valid JSON)
   * @param nowS current time (epoch seconds) used for audit columns
   * @return result describing whether the write was applied
   */
  WriteResult put(UUID owner, String key, String jsonValue, long nowS);

  /**
   * Deletes {@code key} for {@code owner} if it exists.
   *
   * @param owner owner UUID
   * @param key attribute name
   * @return result describing whether the delete was applied
   */
  WriteResult remove(UUID owner, String key);

  /** Result of an attribute mutation. */
  record WriteResult(boolean applied, ErrorCode error) {
    public static WriteResult success() {
      return new WriteResult(true, null);
    }

    public static WriteResult failure(ErrorCode error) {
      return new WriteResult(false, error);
    }
  }
}
