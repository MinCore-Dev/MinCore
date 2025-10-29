/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.ledger;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.holarki.api.Players;
import dev.holarki.api.Players.PlayerRef;
import dev.holarki.commands.AdminCommands;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the `/holarki ledger` admin command hierarchy. */
public final class LedgerAdminCommands {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private LedgerAdminCommands() {}

  public static void register(ModuleContext context) {
    Objects.requireNonNull(context, "context");
    if (!context.config().ledger().enabled()) {
      return;
    }
    Services services = context.services();
    context.registerAdminCommandExtension(root -> attach(root, services));
  }

  public static void attach(
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
        "SELECT l.id, l.ts_s, l.module_id, l.op, l.from_uuid, l.to_uuid, pf.name, pt.name,"
            + " l.amount, l.reason, l.ok, l.code, l.seq, l.idem_scope, l.old_units,"
            + " l.new_units, l.server_node, l.extra_json "
            + "FROM core_ledger l "
            + "LEFT JOIN players pf ON pf.uuid = l.from_uuid "
            + "LEFT JOIN players pt ON pt.uuid = l.to_uuid "
            + "ORDER BY l.id DESC LIMIT ?";
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
        "SELECT l.id, l.ts_s, l.module_id, l.op, l.from_uuid, l.to_uuid, pf.name, pt.name,"
            + " l.amount, l.reason, l.ok, l.code, l.seq, l.idem_scope, l.old_units,"
            + " l.new_units, l.server_node, l.extra_json "
            + "FROM core_ledger l "
            + "LEFT JOIN players pf ON pf.uuid = l.from_uuid "
            + "LEFT JOIN players pt ON pt.uuid = l.to_uuid "
            + "WHERE l.from_uuid = ? OR l.to_uuid = ? ORDER BY l.id DESC LIMIT ?";
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
        "SELECT l.id, l.ts_s, l.module_id, l.op, l.from_uuid, l.to_uuid, pf.name, pt.name,"
            + " l.amount, l.reason, l.ok, l.code, l.seq, l.idem_scope, l.old_units,"
            + " l.new_units, l.server_node, l.extra_json "
            + "FROM core_ledger l "
            + "LEFT JOIN players pf ON pf.uuid = l.from_uuid "
            + "LEFT JOIN players pt ON pt.uuid = l.to_uuid "
            + "WHERE l.module_id = ? ORDER BY l.id DESC LIMIT ?";
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
        "SELECT l.id, l.ts_s, l.module_id, l.op, l.from_uuid, l.to_uuid, pf.name, pt.name,"
            + " l.amount, l.reason, l.ok, l.code, l.seq, l.idem_scope, l.old_units,"
            + " l.new_units, l.server_node, l.extra_json "
            + "FROM core_ledger l "
            + "LEFT JOIN players pf ON pf.uuid = l.from_uuid "
            + "LEFT JOIN players pt ON pt.uuid = l.to_uuid "
            + "WHERE l.reason LIKE ? ORDER BY l.id DESC LIMIT ?";
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
                  rs.getString(7),
                  rs.getString(8),
                  rs.getLong(9),
                  rs.getString(10),
                  rs.getBoolean(11),
                  rs.getString(12),
                  rs.getLong(13),
                  rs.getString(14),
                  readNullableLong(rs, 15),
                  readNullableLong(rs, 16),
                  rs.getString(17),
                  rs.getString(18)));
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
      String from = formatPlayer(row.from(), row.fromName());
      String to = formatPlayer(row.to(), row.toName());
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
    Set<UUID> participants = new HashSet<>();
    for (LedgerRow row : rows) {
      if (row.from() != null) {
        participants.add(row.from());
      }
      if (row.to() != null) {
        participants.add(row.to());
      }
    }
    LOG.debug(
        "(holarki) op={} rows={} uniqueParticipants={} lookupMode=joined",
        "/holarki ledger",
        rows.size(),
        participants.size());
    return 1;
  }

  private static String formatPlayer(UUID uuid, String resolvedName) {
    if (uuid == null) {
      return "-";
    }
    if (resolvedName != null && !resolvedName.isBlank()) {
      return resolvedName;
    }
    return uuid.toString();
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
      String fromName,
      String toName,
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
