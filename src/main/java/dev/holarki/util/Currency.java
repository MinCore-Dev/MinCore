/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Minimal currency helper for integer units (no floating point). */
public final class Currency {
  private static final ThreadLocal<DecimalFormat> GROUP =
      ThreadLocal.withInitial(
          () -> new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.ROOT)));

  private Currency() {}

  /**
   * Format smallest currency units (long) into a human-readable string with grouping separators.
   *
   * @param units amount in smallest units
   * @return formatted amount like "1,234"
   */
  public static String format(long units) {
    return GROUP.get().format(units);
  }

  /**
   * Format using compact suffixes ({@code k}, {@code M}, {@code B}) for large values.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code formatCompact(950)} ⇒ {@code "950"}
   *   <li>{@code formatCompact(12_000)} ⇒ {@code "12.00k"}
   *   <li>{@code formatCompact(3_500_000)} ⇒ {@code "3.50M"}
   * </ul>
   *
   * @param units amount in smallest units
   * @return compact formatted string
   */
  public static String formatCompact(long units) {
    if (Math.abs(units) >= 1_000_000_000L) {
      return String.format(Locale.ROOT, "%.2fB", units / 1_000_000_000.0);
    }
    if (Math.abs(units) >= 1_000_000L) {
      return String.format(Locale.ROOT, "%.2fM", units / 1_000_000.0);
    }
    if (Math.abs(units) >= 1_000L) {
      return String.format(Locale.ROOT, "%.2fk", units / 1_000.0);
    }
    return Long.toString(units);
  }

  /**
   * Parse a human input like "1_000" or "1,000" into smallest currency units.
   *
   * @param s input string
   * @return parsed amount in smallest units
   * @throws IllegalArgumentException if not a valid non-negative number
   */
  public static long parse(String s) {
    if (s == null) {
      throw new IllegalArgumentException("Input may not be null");
    }
    String cleaned = s.replace("_", "").replace(",", "").trim();
    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException("Bad number: " + s);
    }

    long multiplier = 1L;
    String lower = cleaned.toLowerCase(Locale.ROOT);
    if (lower.endsWith("k")) {
      multiplier = 1_000L;
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    } else if (lower.endsWith("m")) {
      multiplier = 1_000_000L;
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    } else if (lower.endsWith("b")) {
      multiplier = 1_000_000_000L;
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    }

    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException("Bad number: " + s);
    }

    BigDecimal base;
    try {
      base = new BigDecimal(cleaned);
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Bad number: " + s, nfe);
    }

    BigDecimal result = base.multiply(BigDecimal.valueOf(multiplier));
    if (result.signum() < 0) {
      throw new IllegalArgumentException("Negative not allowed");
    }

    BigDecimal normalized = result.stripTrailingZeros();
    if (normalized.scale() > 0) {
      throw new IllegalArgumentException("Fractional currency units not supported: " + s);
    }

    BigDecimal max = BigDecimal.valueOf(Long.MAX_VALUE);
    if (normalized.compareTo(max) > 0) {
      throw new IllegalArgumentException("Magnitude exceeds Long.MAX_VALUE: " + s);
    }

    return normalized.longValueExact();
  }
}
