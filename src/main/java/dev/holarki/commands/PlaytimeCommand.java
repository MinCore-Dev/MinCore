/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.holarki.api.Players;
import dev.holarki.api.Playtime;
import dev.holarki.core.Services;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements /playtime me|top|reset. */
public final class PlaytimeCommand {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

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
                                      Text.translatable("holarki.err.player.unknown"));
                                  return 0;
                                }
                                UUID uuid = profiles.iterator().next().getId();
                                return cmdReset(ctx.getSource(), services, uuid);
                              })));
          dispatcher.register(root);
        });
  }

  private static int cmdMe(ServerCommandSource src, Services services) {
    Playtime playtime = services.playtime();
    try {
      var player = src.getPlayer();
      if (player == null) {
        srcSend(src, Text.translatable("holarki.err.player.unknown"));
        return 0;
      }
      long seconds = playtime.seconds(player.getUuid());
      srcSend(src, Text.translatable("holarki.cmd.pt.me", Playtime.human(seconds)));
      return 1;
    } catch (Exception e) {
      srcSend(src, Text.translatable("holarki.err.player.unknown"));
      return 0;
    }
  }

  private static int cmdTop(ServerCommandSource src, Services services, int limit) {
    Playtime playtime = services.playtime();
    List<Playtime.Entry> entries = playtime.top(limit);
    if (entries.isEmpty()) {
      srcSend(src, Text.translatable("holarki.cmd.pt.top.empty"));
      return 1;
    }
    List<Playtime.Entry> snapshot = List.copyOf(entries);
    LinkedHashSet<UUID> uuidOrder = new LinkedHashSet<>();
    for (Playtime.Entry entry : snapshot) {
      UUID player = entry.player();
      if (player != null) {
        uuidOrder.add(player);
      }
    }
    var server = src.getServer();
    var scheduler = services.scheduler();
    if (server == null || scheduler == null) {
      sendTopResult(src, limit, snapshot, Map.of());
      return 1;
    }
    Runnable fallback = () -> server.execute(() -> sendTopResult(src, limit, snapshot, Map.of()));
    try {
      scheduler.execute(
          () -> {
            Map<UUID, Players.PlayerRef> resolved = Map.of();
            try {
              if (!uuidOrder.isEmpty()) {
                resolved = Map.copyOf(services.players().byUuidBulk(uuidOrder));
              }
            } catch (RuntimeException e) {
              LOG.warn("(holarki) playtime top name resolution failed", e);
              resolved = Map.of();
            }
            Map<UUID, Players.PlayerRef> finalResolved = resolved;
            server.execute(() -> sendTopResult(src, limit, snapshot, finalResolved));
          });
    } catch (RejectedExecutionException e) {
      LOG.debug("(holarki) scheduler rejected playtime top task", e);
      fallback.run();
    }
    return 1;
  }

  private static int cmdReset(ServerCommandSource src, Services services, UUID target) {
    Playtime playtime = services.playtime();
    playtime.reset(target);
    srcSend(src, Text.translatable("holarki.cmd.pt.reset.ok"));
    return 1;
  }

  private static void srcSend(ServerCommandSource src, Text text) {
    src.sendFeedback(() -> text, false);
  }

  private static void sendTopResult(
      ServerCommandSource src,
      int limit,
      List<Playtime.Entry> entries,
      Map<UUID, Players.PlayerRef> players) {
    srcSend(src, Text.translatable("holarki.cmd.pt.top.header", limit));
    for (int i = 0; i < entries.size(); i++) {
      Playtime.Entry entry = entries.get(i);
      String name = entry.playerString();
      if (players != null && entry.player() != null) {
        Players.PlayerRef ref = players.get(entry.player());
        if (ref != null && ref.name() != null && !ref.name().isBlank()) {
          name = ref.name();
        }
      }
      srcSend(
          src,
          Text.translatable(
              "holarki.cmd.pt.top.line", i + 1, name, Playtime.human(entry.seconds())));
    }
  }
}
