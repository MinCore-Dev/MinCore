/* MinCore © 2025 — MIT */
package dev.mincore.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.mincore.api.Players;
import dev.mincore.api.Players.PlayerRef;
import dev.mincore.api.Playtime;
import dev.mincore.core.Services;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Implements /playtime me|top|reset. */
public final class PlaytimeCommand {
  private PlaytimeCommand() {}

  /**
   * Registers the `/playtime` command hierarchy.
   *
   * @param services service container providing playtime + players access
   */
  public static void register(Services services) {
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> {
          var root = CommandManager.literal("playtime");
          root.then(CommandManager.literal("me").executes(ctx -> cmdMe(ctx.getSource(), services)));
          root.then(
              CommandManager.literal("top")
                  .then(
                      CommandManager.argument("limit", IntegerArgumentType.integer(1, 100))
                          .executes(
                              ctx ->
                                  cmdTop(
                                      ctx.getSource(),
                                      services,
                                      IntegerArgumentType.getInteger(ctx, "limit"))))
                  .executes(ctx -> cmdTop(ctx.getSource(), services, 10)));
          root.then(
              CommandManager.literal("reset")
                  .requires(src -> src.hasPermissionLevel(4))
                  .then(
                      CommandManager.argument("player", GameProfileArgumentType.gameProfile())
                          .executes(
                              ctx -> {
                                var profiles =
                                    GameProfileArgumentType.getProfileArgument(ctx, "player");
                                if (profiles.isEmpty()) {
                                  srcSend(
                                      ctx.getSource(),
                                      Text.translatable("mincore.err.player.unknown"));
                                  return 0;
                                }
                                UUID uuid = profiles.iterator().next().getId();
                                return cmdReset(ctx.getSource(), services, uuid);
                              })));
          dispatcher.register(root);
        });
  }

  private static int cmdMe(ServerCommandSource src, Services services) {
    Playtime playtime = services.playtime().orElse(null);
    if (playtime == null) {
      srcSend(src, Text.translatable("mincore.cmd.pt.disabled"));
      return 0;
    }
    try {
      var player = src.getPlayer();
      if (player == null) {
        srcSend(src, Text.translatable("mincore.err.player.unknown"));
        return 0;
      }
      long seconds = playtime.seconds(player.getUuid());
      srcSend(src, Text.translatable("mincore.cmd.pt.me", Playtime.human(seconds)));
      return 1;
    } catch (Exception e) {
      srcSend(src, Text.translatable("mincore.err.player.unknown"));
      return 0;
    }
  }

  private static int cmdTop(ServerCommandSource src, Services services, int limit) {
    Playtime playtime = services.playtime().orElse(null);
    if (playtime == null) {
      srcSend(src, Text.translatable("mincore.cmd.pt.disabled"));
      return 0;
    }
    List<Playtime.Entry> entries = playtime.top(limit);
    if (entries.isEmpty()) {
      srcSend(src, Text.translatable("mincore.cmd.pt.top.empty"));
      return 1;
    }
    srcSend(src, Text.translatable("mincore.cmd.pt.top.header", limit));
    Players players = services.players();
    for (int i = 0; i < entries.size(); i++) {
      Playtime.Entry entry = entries.get(i);
      PlayerRef ref = players.byUuid(entry.player()).orElse(null);
      String name = ref != null ? ref.name() : entry.playerString();
      srcSend(
          src,
          Text.translatable(
              "mincore.cmd.pt.top.line", i + 1, name, Playtime.human(entry.seconds())));
    }
    return 1;
  }

  private static int cmdReset(ServerCommandSource src, Services services, UUID target) {
    Playtime playtime = services.playtime().orElse(null);
    if (playtime == null) {
      srcSend(src, Text.translatable("mincore.cmd.pt.disabled"));
      return 0;
    }
    playtime.reset(target);
    srcSend(src, Text.translatable("mincore.cmd.pt.reset.ok"));
    return 1;
  }

  private static void srcSend(ServerCommandSource src, Text text) {
    src.sendFeedback(() -> text, false);
  }
}
