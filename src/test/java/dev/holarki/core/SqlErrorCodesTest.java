/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.holarki.api.ErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SqlErrorCodes}. */
final class SqlErrorCodesTest {

  @Test
  void duplicateSqlStateMapsToDuplicateKey() {
    SQLException sql = new SQLException("Duplicate entry 'x' for key 'PRIMARY'", "23000", 1062);
    assertEquals(ErrorCode.DUPLICATE_KEY, SqlErrorCodes.classify(sql));
  }

  @Test
  void duplicateVendorCodeMapsWithoutSqlState() {
    SQLException sql = new SQLException("Duplicate entry 'x' for key 'PRIMARY'", null, 1062);
    assertEquals(ErrorCode.DUPLICATE_KEY, SqlErrorCodes.classify(sql));
  }

  @Test
  void duplicateMessageFallbackMapsWhenStateAndVendorMissing() {
    SQLException sql = new SQLException("Unique constraint violated on players.name");
    assertEquals(ErrorCode.DUPLICATE_KEY, SqlErrorCodes.classify(sql));
  }
}
