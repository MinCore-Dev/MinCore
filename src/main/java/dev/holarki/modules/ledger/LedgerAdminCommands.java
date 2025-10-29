/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.ledger;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.holarki.commands.AdminCommands;
import dev.holarki.api.Players;
import dev.holarki.api.Players.PlayerRef;
import dev.holarki.core.Services;
import dev.holarki.core.modules.ModuleContext;
import dev.holarki.util.TimeDisplay;
import dev.holarki.util.TimePreference;
import dev.holarki.util.Timezones;
import dev.holarki.util.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Registers the `/holarki ledger` admin command hierarchy. */
final class LedgerAdminCommands {

  private LedgerAdminCommands() {}

  static void register(ModuleContext context) {
    Objects.requireNonNull(context, "context");
    if (!context.config().ledger().enabled()) {
      return;
    }
    Services services = context.services();
    context.registerAdminCommandExtension(root -> attach(root, services));
  }

  static void attach(
      final LiteralArgumentBuilder<ServerCommandSource> root, final Services services) {
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
        CommandManager.literal("module")
            .then(
                CommandManager.argument("moduleId", StringArgumentType.string())
                    .then(
                        CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
                            .executes(
                                ctx ->
                                    cmdLedgerByModule(
                                        ctx.getSource(),
                                        services,
                                        StringArgumentType.getString(ctx, "moduleId"),
                                        IntegerArgumentType.getInteger(ctx, "limit"))))
                    .executes(
                        ctx ->
                            cmdLedgerByModule(
                                ctx.getSource(),
                                services,
                                StringArgumentType.getString(ctx, "moduleId"),
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
    root.then(ledger);
  }

  private static int cmdLedgerRecent(
      final ServerCommandSource src, final Services services, final int limit) {
    final String sql =
        "SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY id DESC LIMIT ?";
    return printLedger(src, services, sql, ps -> ps.setInt(1, Math.max(1, Math.min(200, limit))));
  }

  private static int cmdLedgerByPlayer(
      final ServerCommandSource src,
      final Services services,
      final String target,
      final int limit) {
    UUID resolved = resolvePlayer(src, services, target);
    if (resolved == null) {
      return 0;
    }
    final UUID u = resolved;
    final String sql =
        "SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE from_uuid = ? OR to_uuid = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          byte[] b = Uuids.toBytes(u);
          ps.setBytes(1, b);
          ps.setBytes(2, b);
          ps.setInt(3, Math.max(1, Math.min(200, limit)));
        });
  }

  private static int cmdLedgerByModule(
      final ServerCommandSource src,
      final Services services,
      final String moduleId,
      final int limit) {
    final String sql =
        "SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE module_id = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          ps.setString(1, moduleId);
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  private static int cmdLedgerByReason(
      final ServerCommandSource src,
      final Services services,
      final String needle,
      final int limit) {
    final String sql =
        "SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE reason LIKE ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          ps.setString(1, "%" + needle + "%");
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  private static UUID resolvePlayer(
      final ServerCommandSource src, final Services services, final String target) {
    UUID uuid = tryParseUuid(target);
    if (uuid != null) {
      return uuid;
    }
    Players players = services.players();
    List<PlayerRef> matches = players.byNameAll(target);
    if (matches.isEmpty()) {
      src.sendFeedback(() -> Text.translatable("holarki.err.player.unknown"), false);
      return null;
    }
    if (matches.size() > 1) {
      src.sendFeedback(() -> Text.translatable("holarki.err.player.ambiguous"), false);
      return null;
    }
    return matches.get(0).uuid();
  }

  private static int printLedger(
      final ServerCommandSource src,
      final Services services,
      final String sql,
      final Binder binder) {
    List<LedgerRow> rows = new ArrayList<>();
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new LedgerRow(
                  rs.getLong(1),
                  rs.getLong(2),
                  rs.getString(3),
                  rs.getString(4),
                  readUuid(rs, 5),
                  readUuid(rs, 6),
                  rs.getLong(7),
                  rs.getString(8),
                  rs.getBoolean(9),
                  rs.getString(10),
                  rs.getLong(11),
                  rs.getString(12),
                  readNullableLong(rs, 13),
                  readNullableLong(rs, 14),
                  rs.getString(15),
                  rs.getString(16)));
        }
      }
    } catch (Exception e) {
      AdminCommands.logAdminFailure("/holarki ledger", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.ledger.error", e.getClass().getSimpleName()), false);
      return 0;
    }

    if (rows.isEmpty()) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.ledger.none"), false);
      return 1;
    }

    TimePreference pref = Timezones.preferences(src, services);
    Players players = services.players();
    String offset = TimeDisplay.offsetLabel(pref.zone());
    src.sendFeedback(
        () ->
            Text.translatable(
                "holarki.cmd.ledger.header",
                rows.size(),
                pref.zone().getId(),
                offset,
                pref.clock().description()),
        false);
    for (LedgerRow row : rows) {
      String when = TimeDisplay.formatDateTime(Instant.ofEpochSecond(row.ts()), pref);
      String from = formatPlayer(players, row.from());
      String to = formatPlayer(players, row.to());
      src.sendFeedback(
          () ->
              Text.translatable(
                  "holarki.cmd.ledger.line",
                  row.id(),
                  when,
                  row.module(),
                  row.op(),
                  row.amount(),
                  row.ok(),
                  formatOptional(row.code()),
                  formatSeq(row.seq()),
                  formatOptional(row.scope()),
                  from,
                  to,
                  row.reason(),
                  formatOptional(row.oldUnits()),
                  formatOptional(row.newUnits()),
                  formatOptional(row.serverNode()),
                  formatExtra(row.extraJson())),
          false);
    }
    return 1;
  }

  private static String formatPlayer(Players players, UUID uuid) {
    if (uuid == null) {
      return "-";
    }
    PlayerRef ref = players.byUuid(uuid).orElse(null);
    return ref != null ? ref.name() : uuid.toString();
  }

  private static String formatOptional(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value;
  }

  private static String formatOptional(Long value) {
    if (value == null) {
      return "-";
    }
    return Long.toString(value);
  }

  private static String formatSeq(long seq) {
    return seq > 0 ? Long.toString(seq) : "-";
  }

  private static String formatExtra(String extra) {
    if (extra == null || extra.isBlank()) {
      return "-";
    }
    String trimmed = extra.trim();
    if (trimmed.length() > 80) {
      return trimmed.substring(0, 80) + "…";
    }
    return trimmed;
  }

  private static Long readNullableLong(ResultSet rs, int index) throws SQLException {
    long value = rs.getLong(index);
    return rs.wasNull() ? null : value;
  }

  private static UUID readUuid(ResultSet rs, int index) throws SQLException {
    byte[] bytes = rs.getBytes(index);
    if (bytes == null || bytes.length != 16) {
      return null;
    }
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xff);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (bytes[i] & 0xff);
    }
    return new UUID(msb, lsb);
  }

  private static UUID tryParseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private record LedgerRow(
      long id,
      long ts,
      String module,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code,
      long seq,
      String scope,
      Long oldUnits,
      Long newUnits,
      String serverNode,
      String extraJson) {}

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }
}
