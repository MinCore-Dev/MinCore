/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.holarki.HolarkiMod;
import dev.holarki.api.AttributeWriteException;
import dev.holarki.api.Attributes;
import dev.holarki.api.Attributes.WriteResult;
import dev.holarki.api.ErrorCode;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;

/** Helper for resolving/storing timezone overrides. */
public final class Timezones {
  private static final String ATTR_KEY = "holarki.tz";
  private static final List<String> TWELVE_HOUR_PREFIXES =
      List.of(
          "America/",
          "Pacific/Honolulu",
          "Pacific/Midway",
          "Pacific/Pago_Pago",
          "Pacific/Rarotonga",
          "Atlantic/Bermuda",
          "Atlantic/Stanley",
          "Atlantic/Faeroe");

  private Timezones() {}

  /**
   * Resolves the effective ZoneId for a command source.
   *
   * @param src command source issuing the request
   * @param services service container used to query attributes
   * @return preferred zone for the player or the server default
   */
  public static ZoneId resolve(ServerCommandSource src, Services services) {
    return preferences(src, services).zone();
  }

  /**
   * Resolves a player's override if present.
   *
   * @param uuid player identifier
   * @param services service container used to query attributes
   * @return optional zone override
   */
  public static Optional<ZoneId> resolve(UUID uuid, Services services) {
    return resolvePreference(uuid, services).map(TimePreference::zone);
  }

  /**
   * Stores a timezone override for the player.
   *
   * @param uuid player identifier
   * @param zone zone to persist
   * @param services service container used to persist attributes
   */
  public static TimePreference set(UUID uuid, ZoneId zone, Services services) {
    return write(uuid, zone, null, services, "manual");
  }

  /**
   * Stores an auto-detected timezone override for the player.
   *
   * @param uuid player identifier
   * @param zone zone to persist
   * @param services service container used to persist attributes
   */
  public static TimePreference setAuto(UUID uuid, ZoneId zone, Services services) {
    return write(uuid, zone, null, services, "auto");
  }

  /**
   * Stores a clock preference for the player, preserving the existing zone.
   *
   * @param uuid player identifier
   * @param clock clock preference
   * @param services service container used to persist attributes
   * @return updated preferences after the change
   */
  public static TimePreference setClock(UUID uuid, ClockFormat clock, Services services) {
    if (uuid == null) {
      throw new IllegalArgumentException("uuid cannot be null");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock cannot be null");
    }
    TimePreference existing = resolvePreference(uuid, services).orElse(null);
    ZoneId zone = existing != null ? existing.zone() : defaultZone();
    String source = existing != null ? existing.source() : "manual";
    return write(uuid, zone, clock, services, source);
  }

  private static TimePreference write(
      UUID uuid, ZoneId zone, ClockFormat overrideClock, Services services, String sourceOverride) {
    long now = System.currentTimeMillis() / 1000L;
    TimePreference existing = resolvePreference(uuid, services).orElse(null);
    ClockFormat clock =
        overrideClock != null
            ? overrideClock
            : existing != null ? existing.clock() : defaultClock(zone);
    String source =
        sourceOverride != null && !sourceOverride.isBlank()
            ? sourceOverride
            : existing != null ? existing.source() : "manual";

    JsonObject obj = new JsonObject();
    obj.addProperty("zone", zone.getId());
    obj.addProperty("clock", clock.name());
    obj.addProperty("updatedAt", now);
    obj.addProperty("source", source);
    WriteResult result = services.attributes().put(uuid, ATTR_KEY, obj.toString(), now);
    if (!result.applied()) {
      throw attributeWriteFailed(result.error(), "timezone preference");
    }
    return new TimePreference(zone, clock, source, now);
  }

  /**
   * Deletes a timezone override for the player.
   *
   * @param uuid player identifier
   * @param services service container used to persist attributes
   */
  public static void clear(UUID uuid, Services services) {
    WriteResult result = services.attributes().remove(uuid, ATTR_KEY);
    if (!result.applied()) {
      throw attributeWriteFailed(result.error(), "clear timezone preference");
    }
  }

  /**
   * Returns the configured default zone.
   *
   * @return configured default timezone or UTC if unavailable
   */
  public static ZoneId defaultZone() {
    Config cfg = HolarkiMod.config();
    return cfg != null ? cfg.time().display().defaultZone() : ZoneId.of("UTC");
  }

  /** Returns the default preference bundle. */
  public static TimePreference defaults() {
    ZoneId zone = defaultZone();
    return new TimePreference(zone, defaultClock(zone), "default", 0L);
  }

  /**
   * Whether player overrides are allowed.
   *
   * @return {@code true} if `/timezone set` is permitted
   */
  public static boolean overridesAllowed() {
    Config cfg = HolarkiMod.config();
    return cfg != null && cfg.time().display().allowPlayerOverride();
  }

  /** Resolves preferences for a command source or falls back to defaults. */
  public static TimePreference preferences(ServerCommandSource src, Services services) {
    if (src != null) {
      try {
        if (src.getEntity() != null) {
          return preferences(src.getEntity().getUuid(), services);
        }
      } catch (Exception ignored) {
      }
    }
    return defaults();
  }

  /** Resolves preferences for a player id. */
  public static TimePreference preferences(UUID uuid, Services services) {
    return resolvePreference(uuid, services).orElse(defaults());
  }

  /** Attempts to decode stored preferences. */
  public static Optional<TimePreference> resolvePreference(UUID uuid, Services services) {
    if (uuid == null || services == null) {
      return Optional.empty();
    }
    Attributes attributes = services.attributes();
    return attributes.get(uuid, ATTR_KEY).flatMap(Timezones::decode);
  }

  private static AttributeWriteException attributeWriteFailed(ErrorCode error, String action) {
    ErrorCode resolved = error != null ? error : ErrorCode.CONNECTION_LOST;
    return new AttributeWriteException(resolved, "Failed to " + action);
  }

  private static Optional<TimePreference> decode(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
      if (!obj.has("zone")) {
        return Optional.empty();
      }
      ZoneId zone = ZoneId.of(obj.get("zone").getAsString());
      ClockFormat clock =
          obj.has("clock") ? parseClock(obj.get("clock").getAsString(), zone) : defaultClock(zone);
      String source = obj.has("source") ? obj.get("source").getAsString() : "manual";
      long updatedAt = obj.has("updatedAt") ? obj.get("updatedAt").getAsLong() : 0L;
      return Optional.of(new TimePreference(zone, clock, source, updatedAt));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private static ClockFormat parseClock(String raw, ZoneId zone) {
    try {
      return ClockFormat.valueOf(raw);
    } catch (Exception ignored) {
    }
    try {
      return ClockFormat.parse(raw);
    } catch (Exception ignored) {
      return defaultClock(zone);
    }
  }

  /** Returns the default clock style for the supplied zone. */
  public static ClockFormat defaultClock(ZoneId zone) {
    if (zone == null) {
      return ClockFormat.TWENTY_FOUR_HOUR;
    }
    String id = zone.getId();
    for (String prefix : TWELVE_HOUR_PREFIXES) {
      if (id.startsWith(prefix)) {
        return ClockFormat.TWELVE_HOUR;
      }
    }
    return ClockFormat.TWENTY_FOUR_HOUR;
  }
}
