/* Holarki © 2025 — MIT */
package dev.holarki.util;

/**
 * Simple tax helpers.
 *
 * <p>All operations are pure functions over integer "units".
 */
public final class Tax {
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
    if (pct <= 0) return 0L;
    return (long) Math.floor(amount * (Math.min(100.0, pct) / 100.0));
  }
}
