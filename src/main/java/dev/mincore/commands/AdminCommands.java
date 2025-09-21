/* MinCore © 2025 — MIT */
package dev.mincore.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mincore.api.Players;
import dev.mincore.api.storage.ExtensionDatabase;
import dev.mincore.core.Services;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admin/diagnostic commands for MinCore under /mincore (permission level 4). */
public final class AdminCommands {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private AdminCommands() {}

  /** Hook once during mod init. Call from MinCoreMod.onInitialize() after bootstrap(). */
  /**
   * Registers all /mincore admin commands with Brigadier.
   *
   * @param services core service container used by command handlers
   */
  public static void register(final Services services) {
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> {
          buildTree(dispatcher, services);
        });
  }

  private static void buildTree(
      final CommandDispatcher<ServerCommandSource> dispatcher, final Services services) {

    // /mincore
    LiteralArgumentBuilder<ServerCommandSource> root =
        CommandManager.literal("mincore").requires(src -> src.hasPermissionLevel(4));

    // /mincore db
    LiteralArgumentBuilder<ServerCommandSource> db = CommandManager.literal("db");
    db.then(CommandManager.literal("ping").executes(ctx -> cmdDbPing(ctx.getSource(), services)));
    db.then(CommandManager.literal("info").executes(ctx -> cmdDbInfo(ctx.getSource(), services)));

    // /mincore diag
    LiteralArgumentBuilder<ServerCommandSource> diag =
        CommandManager.literal("diag").executes(ctx -> cmdDiag(ctx.getSource(), services));

    // /mincore ledger
    LiteralArgumentBuilder<ServerCommandSource> ledger = CommandManager.literal("ledger");

    // /mincore ledger recent [limit]
    ledger.then(
        CommandManager.literal("recent")
            .then(
                CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
                    .executes(
                        ctx ->
                            cmdLedgerRecent(
                                ctx.getSource(),
                                services,
                                IntegerArgumentType.getInteger(ctx, "limit"))))
            .executes(ctx -> cmdLedgerRecent(ctx.getSource(), services, 10)));

    // /mincore ledger player <name|uuid> [limit]
    ledger.then(
        CommandManager.literal("player")
            .then(
                CommandManager.argument("target", StringArgumentType.string())
                    .then(
                        CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
                            .executes(
                                ctx ->
                                    cmdLedgerByPlayer(
                                        ctx.getSource(),
                                        services,
                                        StringArgumentType.getString(ctx, "target"),
                                        IntegerArgumentType.getInteger(ctx, "limit"))))
                    .executes(
                        ctx ->
                            cmdLedgerByPlayer(
                                ctx.getSource(),
                                services,
                                StringArgumentType.getString(ctx, "target"),
                                10))));

    // /mincore ledger reason <substring> [limit]
    ledger.then(
        CommandManager.literal("reason")
            .then(
                CommandManager.argument("substring", StringArgumentType.string())
                    .then(
                        CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
                            .executes(
                                ctx ->
                                    cmdLedgerByReason(
                                        ctx.getSource(),
                                        services,
                                        StringArgumentType.getString(ctx, "substring"),
                                        IntegerArgumentType.getInteger(ctx, "limit"))))
                    .executes(
                        ctx ->
                            cmdLedgerByReason(
                                ctx.getSource(),
                                services,
                                StringArgumentType.getString(ctx, "substring"),
                                10))));

    // /mincore ledger addon <addonId> [limit]
    ledger.then(
        CommandManager.literal("addon")
            .then(
                CommandManager.argument("addonId", StringArgumentType.string())
                    .then(
                        CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
                            .executes(
                                ctx ->
                                    cmdLedgerByAddon(
                                        ctx.getSource(),
                                        services,
                                        StringArgumentType.getString(ctx, "addonId"),
                                        IntegerArgumentType.getInteger(ctx, "limit"))))
                    .executes(
                        ctx ->
                            cmdLedgerByAddon(
                                ctx.getSource(),
                                services,
                                StringArgumentType.getString(ctx, "addonId"),
                                10))));

    root.then(db);
    root.then(diag);
    root.then(ledger);

    dispatcher.register(root);
  }

  // ----------------------------------------------------------------------
  // Handlers
  // ----------------------------------------------------------------------

  private static int cmdDbPing(final ServerCommandSource src, final Services services) {
    long pingMs;
    try (Connection c = services.database().borrowConnection()) {
      long t0 = System.nanoTime();
      try (java.sql.Statement st = c.createStatement()) {
        try (ResultSet rs = st.executeQuery("SELECT 1")) {
          while (rs.next()) {
            // drain
          }
        }
      }
      pingMs = (System.nanoTime() - t0) / 1_000_000L;
      final String msg = "DB OK: connect=pooled, ping=" + pingMs + "ms";
      src.sendFeedback(() -> Text.literal(msg), false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore db ping failed", e);
      final String msg = "DB ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
      src.sendFeedback(() -> Text.literal(msg), false);
      return 0;
    }
  }

  private static int cmdDbInfo(final ServerCommandSource src, final Services services) {
    try (Connection c = services.database().borrowConnection()) {
      java.sql.DatabaseMetaData md = c.getMetaData();
      final String url = safe(md.getURL());
      final String product =
          safe(md.getDatabaseProductName()) + " " + safe(md.getDatabaseProductVersion());
      src.sendFeedback(() -> Text.literal("DB: " + url + " (" + product + ")"), false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore db info failed", e);
      final String msg = "DB ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
      src.sendFeedback(() -> Text.literal(msg), false);
      return 0;
    }
  }

  private static int cmdDiag(final ServerCommandSource src, final Services services) {
    int ok = 1;
    try (Connection c = services.database().borrowConnection()) {
      src.sendFeedback(() -> Text.literal("Database   : OK (borrowed from pool)"), false);
      try (java.sql.Statement st = c.createStatement()) {
        try (ResultSet rs = st.executeQuery("SELECT @@session.time_zone")) {
          if (rs.next()) {
            final String tz = rs.getString(1);
            src.sendFeedback(() -> Text.literal("SessionTZ  : " + tz), false);
          }
        }
      }
    } catch (Exception e) {
      ok = 0;
      src.sendFeedback(
          () -> Text.literal("Database   : ERROR " + e.getClass().getSimpleName()), false);
    }
    return ok;
  }

  private static int cmdLedgerRecent(
      final ServerCommandSource src, final Services services, final int limit) {
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope "
            + "FROM core_ledger "
            + "ORDER BY id DESC "
            + "LIMIT ?";
    return printLedger(src, services.database(), sql, ps -> ps.setInt(1, Math.max(1, limit)));
  }

  private static int cmdLedgerByPlayer(
      final ServerCommandSource src,
      final Services services,
      final String target,
      final int limit) {
    UUID uuid = tryParseUuid(target);
    if (uuid == null) {
      Players.PlayerRef pv = services.players().byName(target).orElse(null);
      if (pv == null) {
        src.sendFeedback(() -> Text.literal("No player matched: " + target), false);
        return 0;
      }
      uuid = pv.uuid();
    }
    final UUID u = uuid;

    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope "
            + "FROM core_ledger "
            + "WHERE from_uuid = ? OR to_uuid = ? "
            + "ORDER BY id DESC "
            + "LIMIT ?";
    return printLedger(
        src,
        services.database(),
        sql,
        ps -> {
          ps.setBytes(1, uuidToBytes(u));
          ps.setBytes(2, uuidToBytes(u));
          ps.setInt(3, Math.max(1, limit));
        });
  }

  private static int cmdLedgerByReason(
      final ServerCommandSource src,
      final Services services,
      final String needle,
      final int limit) {
    final String like = "%" + needle + "%";
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope "
            + "FROM core_ledger "
            + "WHERE reason LIKE ? "
            + "ORDER BY id DESC "
            + "LIMIT ?";
    return printLedger(
        src,
        services.database(),
        sql,
        ps -> {
          ps.setString(1, like);
          ps.setInt(2, Math.max(1, limit));
        });
  }

  private static int cmdLedgerByAddon(
      final ServerCommandSource src,
      final Services services,
      final String addonId,
      final int limit) {
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope "
            + "FROM core_ledger "
            + "WHERE addon_id = ? "
            + "ORDER BY id DESC "
            + "LIMIT ?";
    return printLedger(
        src,
        services.database(),
        sql,
        ps -> {
          ps.setString(1, addonId);
          ps.setInt(2, Math.max(1, limit));
        });
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  @FunctionalInterface
  private interface PStmt {
    void bind(PreparedStatement ps) throws Exception;
  }

  private static int printLedger(
      final ServerCommandSource src,
      final ExtensionDatabase db,
      final String sql,
      final PStmt binder) {
    try (Connection c = db.borrowConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        int count = 0;
        src.sendFeedback(() -> Text.literal("— recent ledger entries —"), false);
        while (rs.next()) {
          long id = rs.getLong("id");
          long ts = rs.getLong("ts_s");
          String addon = safe(rs.getString("addon_id"));
          String op = safe(rs.getString("op"));
          long amount = rs.getLong("amount");
          boolean ok = rs.getBoolean("ok");
          String code = safe(rs.getString("code"));
          long seq = rs.getLong("seq");
          String scope = safe(rs.getString("idem_scope"));
          String fromStr = formatUuid(rs.getBytes("from_uuid"));
          String toStr = formatUuid(rs.getBytes("to_uuid"));
          String reason = safe(rs.getString("reason"));

          StringBuilder line =
              new StringBuilder()
                  .append('#')
                  .append(id)
                  .append(" ts=")
                  .append(ts)
                  .append(" [")
                  .append(addon)
                  .append(':')
                  .append(op)
                  .append("] amt=")
                  .append(amount)
                  .append(" ok=")
                  .append(ok ? "ok" : "fail")
                  .append(" seq=")
                  .append(seq)
                  .append(" scope=")
                  .append(scope.isEmpty() ? "-" : scope)
                  .append(" from=")
                  .append(fromStr)
                  .append(" to=")
                  .append(toStr)
                  .append(" reason=")
                  .append(reason.isEmpty() ? "-" : reason);
          if (!code.isEmpty()) {
            line.append(" code=").append(code);
          }

          final String fline = line.toString();
          src.sendFeedback(() -> Text.literal(fline), false);
          count++;
        }
        if (count == 0) {
          src.sendFeedback(() -> Text.literal("(no rows)"), false);
        }
      }
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) ledger query failed", e);
      final String msg = "Ledger ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
      src.sendFeedback(() -> Text.literal(msg), false);
      return 0;
    }
  }

  private static String safe(String s) {
    return (s == null) ? "" : s;
  }

  private static String formatUuid(byte[] raw) {
    if (raw == null || raw.length != 16) {
      return "-";
    }
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (raw[i] & 0xffL);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (raw[i] & 0xffL);
    }
    return new UUID(msb, lsb).toString();
  }

  private static byte[] uuidToBytes(UUID uuid) {
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();
    byte[] out = new byte[16];
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) ((msb >>> (8 * (7 - i))) & 0xff);
    }
    for (int i = 0; i < 8; i++) {
      out[8 + i] = (byte) ((lsb >>> (8 * (7 - i))) & 0xff);
    }
    return out;
  }

  private static UUID tryParseUuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (Exception ignored) {
      return null;
    }
  }
}
