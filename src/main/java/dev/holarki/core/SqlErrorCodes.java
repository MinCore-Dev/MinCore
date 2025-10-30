/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.ErrorCode;
import java.sql.SQLException;
import java.util.Locale;

/** Utility that maps JDBC {@link SQLException} instances to Holarki {@link ErrorCode}s. */
public final class SqlErrorCodes {

  private SqlErrorCodes() {}

  /**
   * Classifies a SQL exception into one of the canonical {@link ErrorCode} values.
   *
   * @param e SQL exception thrown by MariaDB/MySQL
   * @return mapped {@link ErrorCode}, defaulting to {@link ErrorCode#CONNECTION_LOST}
   */
  public static ErrorCode classify(SQLException e) {
    if (e == null) {
      return ErrorCode.CONNECTION_LOST;
    }
    String state = e.getSQLState();
    if (state != null && state.length() >= 2) {
      switch (state.substring(0, 2)) {
        case "40":
        case "41":
          return ErrorCode.DEADLOCK_RETRY_EXHAUSTED;
        case "55":
          return ErrorCode.MIGRATION_LOCKED;
        case "23":
          return ErrorCode.DUPLICATE_KEY;
        case "08":
        case "28":
          return ErrorCode.CONNECTION_LOST;
        default:
          break;
      }
    }

    int vendor = e.getErrorCode();
    if (vendor == 1213 || vendor == 1205) {
      return ErrorCode.DEADLOCK_RETRY_EXHAUSTED;
    }
    if (isDuplicateVendorCode(vendor)) {
      return ErrorCode.DUPLICATE_KEY;
    }

    String message = e.getMessage();
    if (message != null) {
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("deadlock") || lower.contains("lock wait timeout")) {
        return ErrorCode.DEADLOCK_RETRY_EXHAUSTED;
      }
      if (lower.contains("metadata lock") || lower.contains("advisory lock")) {
        return ErrorCode.MIGRATION_LOCKED;
      }
      if (looksLikeDuplicate(lower)) {
        return ErrorCode.DUPLICATE_KEY;
      }
    }
    return ErrorCode.CONNECTION_LOST;
  }

  private static boolean isDuplicateVendorCode(int vendor) {
    return vendor == 1022 || vendor == 1062 || vendor == 1586 || vendor == 1761;
  }

  private static boolean looksLikeDuplicate(String lowerMessage) {
    if (lowerMessage == null) {
      return false;
    }
    return lowerMessage.contains("duplicate")
        || lowerMessage.contains("unique constraint")
        || lowerMessage.contains("unique index")
        || lowerMessage.contains("primary key");
  }
}
