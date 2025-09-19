/* MinCore © 2025 — MIT */
package dev.mincore.util;

/**
 * Formatting and parsing helpers for values expressed in smallest currency units.
 *
 * <p><strong>Formatting:</strong> large values are abbreviated with suffixes:
 *
 * <ul>
 *   <li>{@code k} for thousands
 *   <li>{@code M} for millions
 *   <li>{@code B} for billions
 * </ul>
 *
 * <p><strong>Parsing:</strong> accepts case-insensitive numeric strings with optional suffixes
 * {@code k}, {@code m}, {@code b} (e.g., {@code "2k"}, {@code "1.5m"}, {@code "3b"}). Invalid input
 * returns {@code 0}.
 */
public final class Money {
  private Money() {}

  /**
   * Formats a value in smallest currency units using compact suffixes.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code 950 -> "950"}
   *   <li>{@code 1_200 -> "1.20k"}
   *   <li>{@code 3_500_000 -> "3.50M"}
   *   <li>{@code 7_250_000_000 -> "7.25B"}
   * </ul>
   *
   * @param units the amount in smallest currency units
   * @return a human-friendly formatted string
   */
  public static String format(long units) {
    if (units >= 1_000_000_000L) return String.format("%.2fB", units / 1_000_000_000.0);
    if (units >= 1_000_000L) return String.format("%.2fM", units / 1_000_000.0);
    if (units >= 1_000L) return String.format("%.2fk", units / 1_000.0);
    return Long.toString(units);
  }

  /**
   * Parses a string with optional suffix into smallest currency units.
   *
   * <p>Accepted forms (case-insensitive):
   *
   * <ul>
   *   <li>Plain integer: {@code "900"}
   *   <li>Thousands: {@code "1.2k"}
   *   <li>Millions: {@code "2m"}, {@code "2.5m"}
   *   <li>Billions: {@code "3b"}, {@code "0.75b"}
   * </ul>
   *
   * <p>If the input cannot be parsed, this method returns {@code 0}.
   *
   * @param s input text
   * @return parsed amount in smallest currency units (or {@code 0} if invalid)
   */
  public static long parse(String s) {
    s = s.trim().toLowerCase();
    try {
      if (s.endsWith("k"))
        return (long) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000);
      if (s.endsWith("m"))
        return (long) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000);
      if (s.endsWith("b"))
        return (long)
            Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000_000);
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
