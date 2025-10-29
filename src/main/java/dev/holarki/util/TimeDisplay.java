/* Holarki © 2025 — MIT */
package dev.holarki.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.util.Locale;

/** Utility for rendering times with per-player preferences. */
public final class TimeDisplay {
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.ENGLISH);
  private static final DateTimeFormatter OFFSET =
      new DateTimeFormatterBuilder()
          .appendLiteral("UTC")
          .appendOffset("+HH:mm", "+00:00")
          .toFormatter(Locale.ENGLISH);

  private TimeDisplay() {}

  /**
   * Formats a full date-time string (yyyy-MM-dd) using the player's preferences.
   *
   * @param instant instant to render
   * @param pref preference bundle
   * @return formatted string like {@code 2025-05-10 2:20:15 PM PST (UTC-08:00)}
   */
  public static String formatDateTime(Instant instant, TimePreference pref) {
    if (instant == null) {
      return "-";
    }
    TimePreference effective = pref != null ? pref : Timezones.defaults();
    ZonedDateTime zoned = instant.atZone(effective.zone());
    return DATE.format(zoned)
        + " "
        + effective.clock().longFormatter().format(zoned)
        + " "
        + offsetLabel(zoned);
  }

  /** Formats a time-only string using the player's preferences. */
  public static String formatTime(Instant instant, TimePreference pref) {
    if (instant == null) {
      return "-";
    }
    TimePreference effective = pref != null ? pref : Timezones.defaults();
    ZonedDateTime zoned = instant.atZone(effective.zone());
    return effective.clock().shortFormatter().format(zoned) + " " + offsetLabel(zoned);
  }

  /** Renders the current timezone label (e.g., PST (UTC-08:00) or GMT). */
  public static String offsetLabel(ZoneId zone) {
    if (zone == null) {
      return "UTC";
    }
    ZonedDateTime now = ZonedDateTime.now(zone);
    return offsetLabel(now);
  }

  private static String offsetLabel(ZonedDateTime zoned) {
    if (zoned == null) {
      return "UTC";
    }
    ZoneId zone = zoned.getZone();
    String shortName = zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    if (shortName.startsWith("GMT") && shortName.length() > 3) {
      shortName = "UTC" + shortName.substring(3);
    }
    String offset = OFFSET.format(zoned);
    if ("UTC".equals(shortName)) {
      return "UTC";
    }
    if ("GMT".equals(shortName) && "UTC+00:00".equals(offset)) {
      return "GMT";
    }
    if (shortName.equals(offset)) {
      return offset;
    }
    return shortName + " (" + offset + ")";
  }
}
