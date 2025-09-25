/* MinCore © 2025 — MIT */
package dev.mincore.util;

import java.time.ZoneId;

/** Stored per-player timezone and clock preferences. */
public record TimePreference(ZoneId zone, ClockFormat clock, String source, long updatedAt) {
  public TimePreference {
    if (zone == null) {
      throw new IllegalArgumentException("zone cannot be null");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock cannot be null");
    }
    if (source == null || source.isBlank()) {
      source = "manual";
    }
  }
}
