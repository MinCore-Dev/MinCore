/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Utility helpers for working with UUID binary representations. */
public final class Uuids {
  private Uuids() {}

  /**
   * Converts a UUID to its compact 16-byte array form.
   *
   * @param uuid value to convert
   * @return 16-byte array representing the UUID
   */
  public static byte[] toBytes(UUID uuid) {
    Objects.requireNonNull(uuid, "uuid");
    byte[] out = new byte[16];
    ByteBuffer.wrap(out)
        .putLong(uuid.getMostSignificantBits())
        .putLong(uuid.getLeastSignificantBits());
    return out;
  }

  /**
   * Parses a canonical UUID string (with dashes) and returns the compact 16-byte representation.
   *
   * @param value canonical UUID string
   * @return 16-byte array or {@code null} if {@code value} is {@code null} or blank
   * @throws IllegalArgumentException if {@code value} is non-blank but not a valid UUID
   */
  public static byte[] fromString(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return toBytes(UUID.fromString(value.trim()));
  }
}
