/* MinCore © 2025 — MIT */
package dev.mincore.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mincore.core.Services;
import dev.mincore.util.Timezones;
import dev.mincore.util.TokenBucketRateLimiter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Implements the /timezone command surface. */
public final class TimezoneCommand {
  private static final TokenBucketRateLimiter PLAYER_RATE_LIMITER =
      new TokenBucketRateLimiter(1, 0.33);

  private TimezoneCommand() {}

  /** Registers the command tree. */
  /**
   * Registers the `/timezone` command hierarchy.
   *
   * @param services service container used for player + attribute access
   */
  public static void register(Services services) {
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> {
          var root = CommandManager.literal("timezone");
          root.executes(ctx -> showHelp(ctx.getSource(), services));
          root.then(
              CommandManager.literal("set")
                  .then(
                      CommandManager.argument("zone", StringArgumentType.string())
                          .executes(
                              ctx ->
                                  setZone(
                                      ctx.getSource(),
                                      services,
                                      StringArgumentType.getString(ctx, "zone")))));
          root.then(
              CommandManager.argument("zone", StringArgumentType.string())
                  .executes(
                      ctx ->
                          setZone(
                              ctx.getSource(),
                              services,
                              StringArgumentType.getString(ctx, "zone"))));
          dispatcher.register(root);
        });
  }

  private static int showHelp(ServerCommandSource src, Services services) {
    if (!allowPlayerRateLimit(src)) {
      return 0;
    }
    ZoneId zone = Timezones.resolve(src, services);
    Text header = Text.translatable("mincore.cmd.tz.help", zone.getId());
    src.sendFeedback(() -> header, false);
    return 1;
  }

  private static int setZone(ServerCommandSource src, Services services, String id) {
    if (!allowPlayerRateLimit(src)) {
      return 0;
    }
    if (!Timezones.overridesAllowed()) {
      src.sendFeedback(() -> Text.translatable("mincore.err.tz.overridesDisabled"), false);
      return 0;
    }
    try {
      ZoneId zone = ZoneId.of(id);
      var player = src.getPlayer();
      if (player == null) {
        src.sendFeedback(() -> Text.translatable("mincore.err.tz.invalid"), false);
        return 0;
      }
      Timezones.set(player.getUuid(), zone, services);
      Text msg =
          Text.translatable(
              "mincore.cmd.tz.set.ok",
              zone.getId(),
              zone.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
      src.sendFeedback(() -> msg, false);
      return 1;
    } catch (Exception e) {
      src.sendFeedback(() -> Text.translatable("mincore.err.tz.invalid"), false);
      return 0;
    }
  }

  private static boolean allowPlayerRateLimit(ServerCommandSource src) {
    try {
      var player = src.getPlayer();
      if (player == null) {
        return true;
      }
      long now = Instant.now().getEpochSecond();
      if (PLAYER_RATE_LIMITER.tryAcquire(player.getUuid().toString(), now)) {
        return true;
      }
      src.sendFeedback(() -> Text.translatable("mincore.err.cmd.rateLimited"), false);
      return false;
    } catch (Exception e) {
      return true;
    }
  }
}
