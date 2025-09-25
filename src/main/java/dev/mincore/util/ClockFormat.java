/* MinCore © 2025 — MIT */
package dev.mincore.util;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Supported player-facing clock formats. */
public enum ClockFormat {
  /** Twelve hour clock (2:20 PM). */
  TWELVE_HOUR(
      "12-hour", DateTimeFormatter.ofPattern("h:mm a"), DateTimeFormatter.ofPattern("h:mm:ss a")),
  /** Twenty four hour clock (14:20). */
  TWENTY_FOUR_HOUR(
      "24-hour", DateTimeFormatter.ofPattern("HH:mm"), DateTimeFormatter.ofPattern("HH:mm:ss"));

  private final String description;
  private final DateTimeFormatter shortFormatter;
  private final DateTimeFormatter longFormatter;

  ClockFormat(
      String description, DateTimeFormatter shortFormatter, DateTimeFormatter longFormatter) {
    this.description = description;
    this.shortFormatter = shortFormatter.withLocale(Locale.ENGLISH);
    this.longFormatter = longFormatter.withLocale(Locale.ENGLISH);
  }

  /** Human-readable description ("12-hour" or "24-hour"). */
  public String description() {
    return description;
  }

  /** Formatter without seconds. */
  public DateTimeFormatter shortFormatter() {
    return shortFormatter;
  }

  /** Formatter including seconds. */
  public DateTimeFormatter longFormatter() {
    return longFormatter;
  }

  /** Parses user-provided strings into a {@link ClockFormat}. */
  public static ClockFormat parse(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("clock format cannot be null");
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "12", "12h", "12hr", "twelve", "twelve_hour", "12-hour", "twelve-hour" -> TWELVE_HOUR;
      case "24", "24h", "24hr", "twentyfour", "twenty_four", "24-hour", "twenty-four" ->
          TWENTY_FOUR_HOUR;
      default -> throw new IllegalArgumentException("unknown clock format: " + raw);
    };
  }
}
