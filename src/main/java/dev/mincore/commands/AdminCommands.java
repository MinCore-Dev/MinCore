/* MinCore © 2025 — MIT */
package dev.mincore.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mincore.MinCoreMod;
import dev.mincore.api.Players;
import dev.mincore.core.BackupExporter;
import dev.mincore.core.Scheduler;
import dev.mincore.core.Services;
import dev.mincore.util.Timezones;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
  private static final DateTimeFormatter LEDGER_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withLocale(Locale.ENGLISH);

  private AdminCommands() {}

  /** Registers all admin commands. */
  public static void register(final Services services) {
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> buildTree(dispatcher, services));
  }

  private static void buildTree(
      final CommandDispatcher<ServerCommandSource> dispatcher, final Services services) {
    LiteralArgumentBuilder<ServerCommandSource> root =
        CommandManager.literal("mincore").requires(src -> src.hasPermissionLevel(4));

    LiteralArgumentBuilder<ServerCommandSource> db = CommandManager.literal("db");
    db.then(CommandManager.literal("ping").executes(ctx -> cmdDbPing(ctx.getSource(), services)));
    db.then(CommandManager.literal("info").executes(ctx -> cmdDbInfo(ctx.getSource(), services)));

    LiteralArgumentBuilder<ServerCommandSource> diag =
        CommandManager.literal("diag").executes(ctx -> cmdDiag(ctx.getSource(), services));

    LiteralArgumentBuilder<ServerCommandSource> ledger = CommandManager.literal("ledger");
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

    LiteralArgumentBuilder<ServerCommandSource> jobs = CommandManager.literal("jobs");
    jobs.then(CommandManager.literal("list").executes(ctx -> cmdJobsList(ctx.getSource())));
    jobs.then(
        CommandManager.literal("run")
            .then(
                CommandManager.argument("job", StringArgumentType.string())
                    .executes(
                        ctx ->
                            cmdJobsRun(
                                ctx.getSource(), StringArgumentType.getString(ctx, "job")))));

    LiteralArgumentBuilder<ServerCommandSource> backup =
        CommandManager.literal("backup")
            .then(
                CommandManager.literal("now")
                    .executes(ctx -> cmdBackupNow(ctx.getSource(), services)));

    root.then(db);
    root.then(diag);
    root.then(ledger);
    root.then(jobs);
    root.then(backup);

    dispatcher.register(root);
  }

  private static int cmdDbPing(final ServerCommandSource src, final Services services) {
    long pingMs;
    try (Connection c = services.database().borrowConnection()) {
      long t0 = System.nanoTime();
      try (PreparedStatement ps = c.prepareStatement("SELECT 1")) {
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            // drain
          }
        }
      }
      pingMs = (System.nanoTime() - t0) / 1_000_000L;
      src.sendFeedback(() -> Text.translatable("mincore.cmd.db.ping.ok", pingMs), false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore db ping failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.db.ping.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static int cmdDbInfo(final ServerCommandSource src, final Services services) {
    try (Connection c = services.database().borrowConnection()) {
      DatabaseMetaData md = c.getMetaData();
      String url = safe(md.getURL());
      String product =
          safe(md.getDatabaseProductName()) + " " + safe(md.getDatabaseProductVersion());
      String isolation = isolationName(c.getTransactionIsolation());
      var cfg = MinCoreMod.config();
      String host = cfg != null ? cfg.db().host() : "?";
      int port = cfg != null ? cfg.db().port() : -1;
      boolean tls = cfg != null && cfg.db().tlsEnabled();
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.db.info.detail",
                  host,
                  port,
                  tls ? "tls" : "plain",
                  product,
                  url,
                  isolation),
          false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore db info failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.db.ping.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static int cmdDiag(final ServerCommandSource src, final Services services) {
    boolean ok = true;
    try (Connection c = services.database().borrowConnection()) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.diag.db.ok"), false);
      ok &= reportSchemaVersion(src, c);
    } catch (Exception e) {
      ok = false;
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.diag.db.fail", e.getClass().getSimpleName()), false);
    }

    boolean lock = services.database().tryAdvisoryLock("mincore_diag");
    if (lock) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.diag.lock.ok"), false);
      services.database().releaseAdvisoryLock("mincore_diag");
    } else {
      ok = false;
      src.sendFeedback(() -> Text.translatable("mincore.cmd.diag.lock.fail"), false);
    }

    return ok ? 1 : 0;
  }

  private static boolean reportSchemaVersion(ServerCommandSource src, Connection c) {
    try (PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM core_schema_version");
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        long version = rs.getLong(1);
        src.sendFeedback(() -> Text.translatable("mincore.cmd.diag.schema", version), false);
        return true;
      }
    } catch (SQLException e) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.diag.schemaMissing"), false);
    }
    return false;
  }

  private static int cmdLedgerRecent(
      final ServerCommandSource src, final Services services, final int limit) {
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY id DESC LIMIT ?";
    return printLedger(src, services, sql, ps -> ps.setInt(1, Math.max(1, limit)));
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
        src.sendFeedback(() -> Text.translatable("mincore.err.player.unknown"), false);
        return 0;
      }
      uuid = pv.uuid();
    }
    final UUID u = uuid;

    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE from_uuid = ? OR to_uuid = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
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
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE reason LIKE ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
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
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE addon_id = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          ps.setString(1, addonId);
          ps.setInt(2, Math.max(1, limit));
        });
  }

  private static int cmdJobsList(ServerCommandSource src) {
    src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.header"), false);
    for (Scheduler.JobStatus status : Scheduler.jobs()) {
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.jobs.line",
                  status.name,
                  status.schedule,
                  status.nextRun,
                  status.lastRun,
                  status.running,
                  status.successCount,
                  status.failureCount,
                  status.lastError == null ? "" : status.lastError),
          false);
    }
    return 1;
  }

  private static int cmdJobsRun(ServerCommandSource src, String job) {
    boolean ok = Scheduler.runNow(job);
    if (ok) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.run.ok", job), false);
      return 1;
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.run.unknown", job), false);
    return 0;
  }

  private static int cmdBackupNow(ServerCommandSource src, Services services) {
    final var server = src.getServer();
    services
        .scheduler()
        .submit(
            () -> {
              try {
                var cfg = MinCoreMod.config();
                if (cfg == null) {
                  server.execute(
                      () ->
                          src.sendFeedback(
                              () -> Text.translatable("mincore.cmd.backup.fail", "config"), false));
                  return;
                }
                var result = BackupExporter.exportAll(services, cfg);
                server.execute(
                    () ->
                        src.sendFeedback(
                            () ->
                                Text.translatable(
                                    "mincore.cmd.backup.ok",
                                    result.file().getFileName().toString(),
                                    result.players(),
                                    result.attributes(),
                                    result.ledger()),
                            false));
              } catch (Exception e) {
                LOG.warn("(mincore) manual backup failed", e);
                server.execute(
                    () ->
                        src.sendFeedback(
                            () -> Text.translatable("mincore.cmd.backup.fail", e.getMessage()),
                            false));
              }
            });
    src.sendFeedback(() -> Text.translatable("mincore.cmd.backup.started"), false);
    return 1;
  }

  private interface Binder {
    void bind(PreparedStatement ps) throws Exception;
  }

  private static int printLedger(
      final ServerCommandSource src,
      final Services services,
      final String sql,
      final Binder binder) {
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        int count = 0;
        src.sendFeedback(() -> Text.translatable("mincore.cmd.ledger.header"), false);
        ZoneId zone = Timezones.resolve(src, services);
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
          String serverNode = safe(rs.getString("server_node"));
          String extra = safe(rs.getString("extra_json"));
          long oldUnits = rs.getLong("old_units");
          long newUnits = rs.getLong("new_units");

          Text line =
              Text.translatable(
                  "mincore.cmd.ledger.line",
                  id,
                  LEDGER_TIME.format(Instant.ofEpochSecond(ts).atZone(zone)),
                  addon,
                  op,
                  amount,
                  ok,
                  code,
                  seq,
                  scope,
                  fromStr,
                  toStr,
                  reason,
                  oldUnits,
                  newUnits,
                  serverNode,
                  extra);
          src.sendFeedback(() -> line, false);
          count++;
        }
        if (count == 0) {
          src.sendFeedback(() -> Text.translatable("mincore.cmd.ledger.none"), false);
        }
      }
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) ledger query failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.ledger.error", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String isolationName(int iso) {
    return switch (iso) {
      case Connection.TRANSACTION_NONE -> "NONE";
      case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
      case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
      case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
      case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
      default -> String.valueOf(iso);
    };
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
