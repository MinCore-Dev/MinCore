/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class TaxTest {
  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

  private static long expected(long amount, double pct) {
    if (Double.isNaN(pct) || pct <= 0.0D) {
      return 0L;
    }

    double clampedPct = Math.min(100.0D, pct);
    BigDecimal amountDecimal = BigDecimal.valueOf(amount);
    BigDecimal pctDecimal = BigDecimal.valueOf(clampedPct);
    return amountDecimal
        .multiply(pctDecimal)
        .divide(ONE_HUNDRED, 0, RoundingMode.FLOOR)
        .longValueExact();
  }

  @Test
  void calcMatchesExactIntegerMathForLongMaxValue() {
    long amount = Long.MAX_VALUE;

    assertEquals(expected(amount, 100.0D), Tax.calc(amount, 100.0D));
    assertEquals(expected(amount, 50.0D), Tax.calc(amount, 50.0D));
    assertEquals(expected(amount, 12.345678901D), Tax.calc(amount, 12.345678901D));
    assertEquals(expected(amount, 0.0000001D), Tax.calc(amount, 0.0000001D));
  }

  @Test
  void calcClampsAndHandlesOutOfRangePercentages() {
    long amount = Long.MAX_VALUE;

    assertEquals(expected(amount, 150.0D), Tax.calc(amount, 150.0D));
    assertEquals(expected(amount, -5.0D), Tax.calc(amount, -5.0D));
    assertEquals(expected(amount, Double.NaN), Tax.calc(amount, Double.NaN));
    assertEquals(expected(amount, Double.POSITIVE_INFINITY), Tax.calc(amount, Double.POSITIVE_INFINITY));
  }
}
