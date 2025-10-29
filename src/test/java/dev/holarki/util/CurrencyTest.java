/* Holarki © 2025 — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CurrencyTest {

  @Nested
  @DisplayName("parse")
  class Parse {
    @Test
    @DisplayName("accepts underscores")
    void acceptsUnderscores() {
      assertEquals(1_000L, Currency.parse("1_000"));
    }

    @Test
    @DisplayName("accepts commas")
    void acceptsCommas() {
      assertEquals(1_000L, Currency.parse("1,000"));
    }

    @Test
    @DisplayName("supports suffix multipliers")
    void supportsSuffixes() {
      assertEquals(1_500L, Currency.parse("1.5k"));
      assertEquals(2_000_000L, Currency.parse("2m"));
      assertEquals(3_000_000_000L, Currency.parse("3b"));
    }

    @Test
    @DisplayName("rejects invalid input")
    void rejectsInvalidInput() {
      assertThrows(IllegalArgumentException.class, () -> Currency.parse("abc"));
      assertThrows(IllegalArgumentException.class, () -> Currency.parse("-1"));
      assertThrows(IllegalArgumentException.class, () -> Currency.parse(null));
    }
  }

  @Test
  @DisplayName("format adds grouping separators")
  void formatAddsSeparators() {
    assertEquals("1,234", Currency.format(1_234L));
  }

  @Test
  @DisplayName("formatCompact uses suffixes")
  void formatCompactUsesSuffixes() {
    assertEquals("950", Currency.formatCompact(950));
    assertEquals("1.50k", Currency.formatCompact(1_500));
    assertEquals("2.00M", Currency.formatCompact(2_000_000));
    assertEquals("3.00B", Currency.formatCompact(3_000_000_000L));
  }
}
