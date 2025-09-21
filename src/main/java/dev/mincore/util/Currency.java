/* MinCore © 2025 — MIT */
package dev.mincore.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Minimal currency helper for integer units (no floating point). */
public final class Currency {
  private static final DecimalFormat GROUP =
      new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.ROOT));

  private Currency() {}

  /**
   * Format smallest currency units (long) into a human-readable string.
   *
   * @param units amount in smallest units
   * @return formatted amount like "1,234"
   */
  public static String format(long units) {
    return GROUP.format(units);
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
    String t =
        s.replace("_", "")
            .replace(",", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    double base;
    long mul = 1L;
    if (t.endsWith("k")) {
      mul = 1_000L;
      t = t.substring(0, t.length() - 1);
    } else if (t.endsWith("m")) {
      mul = 1_000_000L;
      t = t.substring(0, t.length() - 1);
    } else if (t.endsWith("b")) {
      mul = 1_000_000_000L;
      t = t.substring(0, t.length() - 1);
    }
    try {
      base = Double.parseDouble(t);
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Bad number: " + s);
    }
    long v = Math.round(base * mul);
    if (v < 0) throw new IllegalArgumentException("Negative not allowed");
    return v;
  }
}
