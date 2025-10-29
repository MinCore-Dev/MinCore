/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simple tax helpers.
 *
 * <p>All operations are pure functions over integer "units".
 */
public final class Tax {
  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

  /** Not instantiable. */
  private Tax() {}

  /**
   * Computes {@code floor(amount * pct / 100)} in units.
   *
   * @param amount base amount in units
   * @param pct percentage in the range {@code 0..100}; values &lt;0 are treated as 0 and values
   *     &gt;100 are clamped to 100
   * @return tax in units
   */
  public static long calc(long amount, double pct) {
    if (Double.isNaN(pct) || pct <= 0.0D) {
      return 0L;
    }

    double clampedPct = Math.min(100.0D, pct);
    BigDecimal amountDecimal = BigDecimal.valueOf(amount);
    BigDecimal pctDecimal = BigDecimal.valueOf(clampedPct);
    BigDecimal tax = amountDecimal.multiply(pctDecimal).divide(ONE_HUNDRED, 0, RoundingMode.FLOOR);
    return tax.longValueExact();
  }
}
