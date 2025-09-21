/* MinCore © 2025 — MIT */
package dev.mincore.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mincore.MinCoreMod;
import dev.mincore.api.Attributes;
import dev.mincore.core.Config;
import dev.mincore.core.Services;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;

/** Helper for resolving/storing timezone overrides. */
public final class Timezones {
  private static final String ATTR_KEY = "mincore.tz";

  private Timezones() {}

  /** Resolves the effective ZoneId for a command source. */
  public static ZoneId resolve(ServerCommandSource src, Services services) {
    if (src == null) {
      return defaultZone();
    }
    try {
      if (src.getEntity() != null) {
        UUID uuid = src.getEntity().getUuid();
        return resolve(uuid, services).orElse(defaultZone());
      }
    } catch (Exception ignored) {
    }
    return defaultZone();
  }

  /** Resolves a player's override if present. */
  public static Optional<ZoneId> resolve(UUID uuid, Services services) {
    if (uuid == null) {
      return Optional.empty();
    }
    Attributes attributes = services.attributes();
    return attributes
        .get(uuid, ATTR_KEY)
        .flatMap(
            json -> {
              try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("zone")) {
                  return Optional.of(ZoneId.of(obj.get("zone").getAsString()));
                }
              } catch (Exception ignored) {
              }
              return Optional.empty();
            });
  }

  /** Stores a timezone override for the player. */
  public static void set(UUID uuid, ZoneId zone, Services services) {
    JsonObject obj = new JsonObject();
    obj.addProperty("zone", zone.getId());
    obj.addProperty("updatedAt", System.currentTimeMillis() / 1000L);
    services.attributes().put(uuid, ATTR_KEY, obj.toString(), System.currentTimeMillis() / 1000L);
  }

  /** Deletes a timezone override for the player. */
  public static void clear(UUID uuid, Services services) {
    services.attributes().remove(uuid, ATTR_KEY);
  }

  /** Returns the configured default zone. */
  public static ZoneId defaultZone() {
    Config cfg = MinCoreMod.config();
    return cfg != null ? cfg.time().display().defaultZone() : ZoneId.of("UTC");
  }

  /** Whether player overrides are allowed. */
  public static boolean overridesAllowed() {
    Config cfg = MinCoreMod.config();
    return cfg != null && cfg.time().display().allowPlayerOverride();
  }
}
