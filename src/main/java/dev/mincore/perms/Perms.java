/* MinCore © 2025 — MIT */
package dev.mincore.perms;

import com.mojang.authlib.GameProfile;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UserCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permission helper that prefers LuckPerms, then Fabric Permissions API, then vanilla operator
 * levels.
 *
 * <p>This API is intentionally slim and stable so bundled modules, server automation, and operators
 * can depend on it without pulling in optional dependencies themselves. Callers should run
 * permission checks on the server thread when a LuckPerms lookup is required so user data is already
 * cached.
 */
public final class Perms {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final boolean LUCKPERMS_PRESENT =
      isClassPresent("net.luckperms.api.LuckPermsProvider");
  private static final boolean FABRIC_PERMISSIONS_PRESENT =
      isClassPresent("me.lucko.fabric.api.permissions.v0.Permissions");
  private static final AtomicBoolean LUCKPERMS_LOGGED = new AtomicBoolean();
  private static final AtomicBoolean FABRIC_LOGGED = new AtomicBoolean();

  private Perms() {}

  /**
   * Checks whether {@code player} has {@code node} according to LuckPerms, the Fabric Permissions
   * API, or a vanilla operator level fallback.
   *
   * @param player online player to check. Call on the server thread.
   * @param node permission node to evaluate
   * @param opLevelFallback required vanilla op level if no permission plugins are present
   * @return true if the player has the node or sufficient op level
   */
  public static boolean check(ServerPlayerEntity player, String node, int opLevelFallback) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(node, "node");
    return check(PlayerAccess.of(player), node, opLevelFallback);
  }

  /**
   * Checks permissions for the given {@code uuid}.
   *
   * <p>If the player is online the Fabric Permissions API and vanilla fallbacks behave identically
   * to {@link #check(ServerPlayerEntity, String, int)}. When offline, the check falls back to the
   * stored operator level if available.
   *
   * @param server active Minecraft server
   * @param uuid player UUID
   * @param node permission node to evaluate
   * @param opLevelFallback required vanilla op level if no permission plugins are present
   * @return true if the player has the node or sufficient op level
   */
  public static boolean checkUUID(
      MinecraftServer server, UUID uuid, String node, int opLevelFallback) {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(node, "node");
    return check(ServerAccess.of(server), uuid, node, opLevelFallback);
  }

  private static Boolean checkLuckPerms(UUID uuid, String node) {
    if (!LUCKPERMS_PRESENT) {
      return null;
    }
    try {
      LuckPerms luckPerms = LuckPermsProvider.get();
      if (luckPerms == null) {
        return null;
      }
      User user = luckPerms.getUserManager().getUser(uuid);
      if (user == null) {
        return null;
      }
      Tristate result = user.getCachedData().getPermissionData().checkPermission(node);
      return result == Tristate.UNDEFINED ? null : result.asBoolean();
    } catch (IllegalStateException | NoClassDefFoundError error) {
      if (LUCKPERMS_LOGGED.compareAndSet(false, true)) {
        LOG.debug("LuckPerms API unavailable when checking permissions", error);
      }
      return null;
    }
  }

  private static boolean isClassPresent(String className) {
    try {
      Class.forName(className, false, Perms.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  static boolean check(PlayerAccess access, String node, int opLevelFallback) {
    Boolean lpResult = checkLuckPerms(access.uuid(), node);
    if (lpResult != null) {
      return lpResult;
    }

    ServerPlayerEntity entity = access.asEntity();
    if (entity != null && FABRIC_PERMISSIONS_PRESENT) {
      try {
        return me.lucko.fabric.api.permissions.v0.Permissions.check(entity, node, opLevelFallback);
      } catch (NoClassDefFoundError error) {
        if (FABRIC_LOGGED.compareAndSet(false, true)) {
          LOG.debug("Fabric Permissions API present at compile-time but missing at runtime", error);
        }
      }
    }

    return access.hasPermissionLevel(opLevelFallback);
  }

  static boolean check(ServerAccess access, UUID uuid, String node, int opLevelFallback) {
    Boolean lpResult = checkLuckPerms(uuid, node);
    if (lpResult != null) {
      return lpResult;
    }

    ServerPlayerEntity online = access.onlinePlayer(uuid);
    if (online != null && FABRIC_PERMISSIONS_PRESENT) {
      try {
        return me.lucko.fabric.api.permissions.v0.Permissions.check(online, node, opLevelFallback);
      } catch (NoClassDefFoundError error) {
        if (FABRIC_LOGGED.compareAndSet(false, true)) {
          LOG.debug("Fabric Permissions API present at compile-time but missing at runtime", error);
        }
      }
    }

    return access.hasPermissionLevel(uuid, opLevelFallback);
  }

  interface PlayerAccess {
    UUID uuid();

    ServerPlayerEntity asEntity();

    boolean hasPermissionLevel(int opLevel);

    static PlayerAccess of(ServerPlayerEntity player) {
      return new PlayerAccess() {
        @Override
        public UUID uuid() {
          return player.getUuid();
        }

        @Override
        public ServerPlayerEntity asEntity() {
          return player;
        }

        @Override
        public boolean hasPermissionLevel(int opLevel) {
          return player.hasPermissionLevel(opLevel);
        }
      };
    }
  }

  interface ServerAccess {
    ServerPlayerEntity onlinePlayer(UUID uuid);

    boolean hasPermissionLevel(UUID uuid, int opLevel);

    static ServerAccess of(MinecraftServer server) {
      return new ServerAccess() {
        @Override
        public ServerPlayerEntity onlinePlayer(UUID uuid) {
          PlayerManager manager = server.getPlayerManager();
          return manager != null ? manager.getPlayer(uuid) : null;
        }

        @Override
        public boolean hasPermissionLevel(UUID uuid, int opLevel) {
          if (opLevel <= 0) {
            return true;
          }

          PlayerManager manager = server.getPlayerManager();
          if (manager == null) {
            return false;
          }

          ServerPlayerEntity online = manager.getPlayer(uuid);
          if (online != null) {
            return online.hasPermissionLevel(opLevel);
          }

          UserCache cache = server.getUserCache();
          GameProfile profile = cache != null ? cache.getByUuid(uuid).orElse(null) : null;
          if (profile == null) {
            return false;
          }
          return server.getPermissionLevel(profile) >= opLevel;
        }
      };
    }
  }
}
