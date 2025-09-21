/* MinCore © 2025 — MIT */
package dev.mincore.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mincore.MinCoreMod;
import dev.mincore.api.Players;
import dev.mincore.api.Players.PlayerRef;
import dev.mincore.core.BackupExporter;
import dev.mincore.core.BackupImporter;
import dev.mincore.core.Config;
import dev.mincore.core.Migrations;
import dev.mincore.core.Scheduler;
import dev.mincore.core.Services;
import dev.mincore.util.Timezones;
import dev.mincore.util.TokenBucketRateLimiter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admin/diagnostic commands exposed under /mincore (permission level 4). */
public final class AdminCommands {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final DateTimeFormatter LEDGER_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withLocale(Locale.ENGLISH);
  private static final TokenBucketRateLimiter ADMIN_RATE_LIMITER =
      new TokenBucketRateLimiter(4, 0.3);

  private AdminCommands() {}

  /**
   * Registers the `/mincore` command tree.
   *
   * @param services service container backing command handlers
   */
  public static void register(final Services services) {
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> {
          LiteralArgumentBuilder<ServerCommandSource> root =
              CommandManager.literal("mincore").requires(src -> src.hasPermissionLevel(4));

          LiteralArgumentBuilder<ServerCommandSource> db = CommandManager.literal("db");
          db.then(
              CommandManager.literal("ping").executes(ctx -> cmdDbPing(ctx.getSource(), services)));
          db.then(
              CommandManager.literal("info").executes(ctx -> cmdDbInfo(ctx.getSource(), services)));
          root.then(db);

          root.then(
              CommandManager.literal("diag").executes(ctx -> cmdDiag(ctx.getSource(), services)));

          LiteralArgumentBuilder<ServerCommandSource> migrate = CommandManager.literal("migrate");
          migrate.then(
              CommandManager.literal("--check")
                  .executes(ctx -> cmdMigrateCheck(ctx.getSource(), services)));
          migrate.then(
              CommandManager.literal("--apply")
                  .executes(ctx -> cmdMigrateApply(ctx.getSource(), services)));
          root.then(migrate);

          LiteralArgumentBuilder<ServerCommandSource> export = CommandManager.literal("export");
          export.then(
              CommandManager.literal("--all")
                  .then(
                      CommandManager.argument("options", StringArgumentType.greedyString())
                          .executes(
                              ctx ->
                                  cmdExportAll(
                                      ctx.getSource(),
                                      services,
                                      StringArgumentType.getString(ctx, "options"))))
                  .executes(ctx -> cmdExportAll(ctx.getSource(), services, "")));
          root.then(export);

          LiteralArgumentBuilder<ServerCommandSource> restore = CommandManager.literal("restore");
          restore
              .then(
                  CommandManager.argument("options", StringArgumentType.greedyString())
                      .executes(
                          ctx ->
                              cmdRestore(
                                  ctx.getSource(),
                                  services,
                                  StringArgumentType.getString(ctx, "options"))))
              .executes(ctx -> cmdRestore(ctx.getSource(), services, ""));
          root.then(restore);

          LiteralArgumentBuilder<ServerCommandSource> doctor = CommandManager.literal("doctor");
          doctor
              .then(
                  CommandManager.argument("options", StringArgumentType.greedyString())
                      .executes(
                          ctx ->
                              cmdDoctor(
                                  ctx.getSource(),
                                  services,
                                  StringArgumentType.getString(ctx, "options"))))
              .executes(ctx -> cmdDoctor(ctx.getSource(), services, ""));
          root.then(doctor);

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

          LiteralArgumentBuilder<ServerCommandSource> jobs = CommandManager.literal("jobs");
          jobs.then(
              CommandManager.literal("list")
                  .executes(ctx -> cmdJobsList(ctx.getSource(), services)));
          jobs.then(
              CommandManager.literal("run")
                  .then(
                      CommandManager.argument("job", StringArgumentType.string())
                          .executes(
                              ctx ->
                                  cmdJobsRun(
                                      ctx.getSource(), StringArgumentType.getString(ctx, "job")))));
          root.then(jobs);

          root.then(
              CommandManager.literal("backup")
                  .then(
                      CommandManager.literal("now")
                          .executes(ctx -> cmdBackupNow(ctx.getSource(), services))));

          dispatcher.register(root);
        });
  }

  private static int cmdDbPing(final ServerCommandSource src, final Services services) {
    if (!allowAdminRateLimit(src, "db.ping")) {
      return 0;
    }
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps = c.prepareStatement("SELECT 1")) {
      long start = System.nanoTime();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          // drain
        }
      }
      long tookMs = Math.max(0, (System.nanoTime() - start) / 1_000_000L);
      src.sendFeedback(() -> Text.translatable("mincore.cmd.db.ping.ok", tookMs), false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore db ping failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.db.ping.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static int cmdDbInfo(final ServerCommandSource src, final Services services) {
    if (!allowAdminRateLimit(src, "db.info")) {
      return 0;
    }
    try (Connection c = services.database().borrowConnection()) {
      var md = c.getMetaData();
      String url = md.getURL();
      String product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
      String isolation = isolationName(c.getTransactionIsolation());

      Config cfg = MinCoreMod.config();
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
    if (!allowAdminRateLimit(src, "diag")) {
      return 0;
    }
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

  private static int cmdMigrateCheck(final ServerCommandSource src, final Services services) {
    try (Connection c = services.database().borrowConnection();
        PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM core_schema_version");
        ResultSet rs = ps.executeQuery()) {
      long version = 0;
      if (rs.next()) {
        version = rs.getLong(1);
      }
      int expected = Migrations.currentVersion();
      final long current = version;
      final int target = expected;
      if (current >= target) {
        src.sendFeedback(
            () -> Text.translatable("mincore.cmd.migrate.check.ok", current, target), false);
        return 1;
      }
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.migrate.check.pending", current, target), false);
      return 0;
    } catch (SQLException e) {
      LOG.warn("(mincore) /mincore migrate --check failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.migrate.check.fail", e.getClass().getSimpleName()),
          false);
      return 0;
    }
  }

  private static int cmdMigrateApply(final ServerCommandSource src, final Services services) {
    if (!services.database().tryAdvisoryLock("mincore_migrate")) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.migrate.locked"), false);
      return 0;
    }
    try {
      Migrations.apply(services);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.migrate.apply.ok", Migrations.currentVersion()),
          false);
      return 1;
    } catch (RuntimeException e) {
      LOG.warn("(mincore) /mincore migrate --apply failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.migrate.apply.fail", e.getMessage()), false);
      return 0;
    } finally {
      services.database().releaseAdvisoryLock("mincore_migrate");
    }
  }

  private static int cmdExportAll(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    Config cfg = MinCoreMod.config();
    if (cfg == null) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.export.fail", "config"), false);
      return 0;
    }
    try {
      ExportOptions opts = ExportOptions.parse(rawOptions);
      Path outDir = opts.outDir != null ? opts.outDir : Path.of(cfg.jobs().backup().outDir());
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.export.started", outDir.toString()), false);
      BackupExporter.Result result =
          BackupExporter.exportAll(services, cfg, outDir, opts.gzipOverride);
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.export.ok",
                  result.file().toString(),
                  result.players(),
                  result.attributes(),
                  result.ledger()),
          false);
      return 1;
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.export.usage", e.getMessage()), false);
      return 0;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore export failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.export.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static int cmdRestore(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    try {
      RestoreOptions opts = RestoreOptions.parse(rawOptions);
      if (opts.from == null) {
        throw new IllegalArgumentException("--from <dir> required");
      }
      if (opts.mode == BackupImporter.Mode.MERGE && !opts.overwrite) {
        throw new IllegalArgumentException("merge mode requires --overwrite");
      }
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.restore.started",
                  opts.mode.name().toLowerCase(Locale.ROOT),
                  opts.from.toString()),
          false);
      BackupImporter.Result result =
          BackupImporter.restore(services, opts.from, opts.mode, opts.strategy, opts.overwrite);
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.restore.ok",
                  result.source().toString(),
                  result.players(),
                  result.attributes(),
                  result.ledger()),
          false);
      return 1;
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.restore.usage", e.getMessage()), false);
      return 0;
    } catch (IOException | SQLException e) {
      LOG.warn("(mincore) /mincore restore failed", e);
      src.sendFeedback(() -> Text.translatable("mincore.cmd.restore.fail", e.getMessage()), false);
      return 0;
    }
  }

  private static int cmdDoctor(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    DoctorOptions opts;
    try {
      opts = DoctorOptions.parse(rawOptions);
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.usage", e.getMessage()), false);
      return 0;
    }
    if (!opts.hasAny()) {
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.doctor.usage", "specify at least one flag"), false);
      return 0;
    }

    boolean ok = true;
    try (Connection c = services.database().borrowConnection()) {
      if (opts.counts) {
        ok &= doctorCounts(src, c);
      }
      if (opts.fk) {
        ok &= doctorForeignKeys(src, c);
      }
      if (opts.orphans) {
        ok &= doctorOrphans(src, c);
      }
      if (opts.analyze) {
        ok &= doctorAnalyze(src, c);
      }
      if (opts.locks) {
        ok &= doctorLocks(src, services);
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) /mincore doctor failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.doctor.fail", e.getClass().getSimpleName()), false);
      return 0;
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

  private static boolean doctorCounts(ServerCommandSource src, Connection c) throws SQLException {
    String sql =
        "SELECT (SELECT COUNT(*) FROM players) AS players,"
            + " (SELECT COUNT(*) FROM player_attributes) AS attrs,"
            + " (SELECT COUNT(*) FROM core_ledger) AS ledger,"
            + " (SELECT COUNT(*) FROM core_requests) AS requests";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        final long players = rs.getLong("players");
        final long attrs = rs.getLong("attrs");
        final long ledger = rs.getLong("ledger");
        final long requests = rs.getLong("requests");
        src.sendFeedback(
            () -> Text.translatable("mincore.cmd.doctor.counts", players, attrs, ledger, requests),
            false);
      }
    }
    return true;
  }

  private static boolean doctorForeignKeys(ServerCommandSource src, Connection c)
      throws SQLException {
    String sql =
        "SELECT COUNT(*) FROM player_attributes pa"
            + " LEFT JOIN players p ON pa.owner_uuid = p.uuid"
            + " WHERE p.uuid IS NULL";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = rs.next() ? rs.getLong(1) : 0L;
      if (count == 0) {
        src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.fk.ok"), false);
        return true;
      }
      src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.fk.fail", count), false);
      return false;
    }
  }

  private static boolean doctorOrphans(ServerCommandSource src, Connection c) throws SQLException {
    long missingFrom;
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM core_ledger l"
                    + " LEFT JOIN players p ON l.from_uuid = p.uuid"
                    + " WHERE l.from_uuid IS NOT NULL AND p.uuid IS NULL");
        ResultSet rs = ps.executeQuery()) {
      missingFrom = rs.next() ? rs.getLong(1) : 0L;
    }
    long missingTo;
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM core_ledger l"
                    + " LEFT JOIN players p ON l.to_uuid = p.uuid"
                    + " WHERE l.to_uuid IS NOT NULL AND p.uuid IS NULL");
        ResultSet rs = ps.executeQuery()) {
      missingTo = rs.next() ? rs.getLong(1) : 0L;
    }
    long total = missingFrom + missingTo;
    if (total == 0) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.orphans.ok"), false);
      return true;
    }
    src.sendFeedback(
        () -> Text.translatable("mincore.cmd.doctor.orphans.fail", missingFrom, missingTo, total),
        false);
    return false;
  }

  private static boolean doctorAnalyze(ServerCommandSource src, Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("ANALYZE TABLE players");
      st.execute("ANALYZE TABLE player_attributes");
      st.execute("ANALYZE TABLE core_ledger");
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.analyze.ok"), false);
    return true;
  }

  private static boolean doctorLocks(ServerCommandSource src, Services services) {
    if (services.database().tryAdvisoryLock("mincore_doctor_lock")) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.locks.ok"), false);
      services.database().releaseAdvisoryLock("mincore_doctor_lock");
      return true;
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.doctor.locks.fail"), false);
    return false;
  }

  private static List<String> tokenize(String raw) {
    List<String> tokens = new ArrayList<>();
    if (raw == null) {
      return tokens;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return tokens;
    }
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    char quoteChar = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (inQuote) {
        if (ch == quoteChar) {
          inQuote = false;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (ch == '\'' || ch == '"') {
        inQuote = true;
        quoteChar = ch;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (inQuote) {
      throw new IllegalArgumentException("unterminated quote in arguments");
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private static final class ExportOptions {
    final Path outDir;
    final Boolean gzipOverride;

    private ExportOptions(Path outDir, Boolean gzipOverride) {
      this.outDir = outDir;
      this.gzipOverride = gzipOverride;
    }

    static ExportOptions parse(String raw) {
      Path out = null;
      Boolean gzip = null;
      List<String> tokens = tokenize(raw);
      for (int i = 0; i < tokens.size(); i++) {
        String token = tokens.get(i);
        switch (token) {
          case "--out" -> {
            if (i + 1 >= tokens.size()) {
              throw new IllegalArgumentException("--out requires a path");
            }
            out = Path.of(tokens.get(++i));
          }
          case "--gzip" -> {
            if (i + 1 >= tokens.size()) {
              throw new IllegalArgumentException("--gzip requires true|false");
            }
            String val = tokens.get(++i).toLowerCase(Locale.ROOT);
            if (!"true".equals(val) && !"false".equals(val)) {
              throw new IllegalArgumentException("--gzip must be true or false");
            }
            gzip = Boolean.valueOf(val);
          }
          case "" -> {
            // ignore empty
          }
          default -> throw new IllegalArgumentException("unknown option: " + token);
        }
      }
      return new ExportOptions(out, gzip);
    }
  }

  private static final class RestoreOptions {
    final BackupImporter.Mode mode;
    final BackupImporter.FreshStrategy strategy;
    final Path from;
    final boolean overwrite;

    private RestoreOptions(
        BackupImporter.Mode mode,
        BackupImporter.FreshStrategy strategy,
        Path from,
        boolean overwrite) {
      this.mode = mode;
      this.strategy = strategy;
      this.from = from;
      this.overwrite = overwrite;
    }

    static RestoreOptions parse(String raw) {
      BackupImporter.Mode mode = null;
      BackupImporter.FreshStrategy strategy = BackupImporter.FreshStrategy.ATOMIC;
      Path from = null;
      boolean overwrite = false;
      List<String> tokens = tokenize(raw);
      for (int i = 0; i < tokens.size(); i++) {
        String token = tokens.get(i);
        switch (token) {
          case "--mode" -> {
            if (i + 1 >= tokens.size()) {
              throw new IllegalArgumentException("--mode requires fresh|merge");
            }
            String val = tokens.get(++i).toLowerCase(Locale.ROOT);
            mode =
                switch (val) {
                  case "fresh" -> BackupImporter.Mode.FRESH;
                  case "merge" -> BackupImporter.Mode.MERGE;
                  default -> throw new IllegalArgumentException("unknown mode: " + val);
                };
          }
          case "--atomic" -> strategy = BackupImporter.FreshStrategy.ATOMIC;
          case "--staging" -> strategy = BackupImporter.FreshStrategy.STAGING;
          case "--from" -> {
            if (i + 1 >= tokens.size()) {
              throw new IllegalArgumentException("--from requires a path");
            }
            from = Path.of(tokens.get(++i));
          }
          case "--overwrite" -> overwrite = true;
          case "" -> {
            // ignore
          }
          default -> throw new IllegalArgumentException("unknown option: " + token);
        }
      }
      if (mode == null) {
        throw new IllegalArgumentException("--mode required");
      }
      if (mode == BackupImporter.Mode.MERGE) {
        strategy = null;
      }
      return new RestoreOptions(mode, strategy, from, overwrite);
    }
  }

  private static final class DoctorOptions {
    final boolean fk;
    final boolean orphans;
    final boolean counts;
    final boolean analyze;
    final boolean locks;

    private DoctorOptions(
        boolean fk, boolean orphans, boolean counts, boolean analyze, boolean locks) {
      this.fk = fk;
      this.orphans = orphans;
      this.counts = counts;
      this.analyze = analyze;
      this.locks = locks;
    }

    static DoctorOptions parse(String raw) {
      boolean fk = false;
      boolean orphans = false;
      boolean counts = false;
      boolean analyze = false;
      boolean locks = false;
      List<String> tokens = tokenize(raw);
      for (String token : tokens) {
        switch (token) {
          case "--fk" -> fk = true;
          case "--orphans" -> orphans = true;
          case "--counts" -> counts = true;
          case "--analyze" -> analyze = true;
          case "--locks" -> locks = true;
          case "" -> {
            // ignore
          }
          default -> throw new IllegalArgumentException("unknown option: " + token);
        }
      }
      return new DoctorOptions(fk, orphans, counts, analyze, locks);
    }

    boolean hasAny() {
      return fk || orphans || counts || analyze || locks;
    }
  }

  private static boolean ensureLedgerEnabled(final ServerCommandSource src) {
    Config cfg = MinCoreMod.config();
    if (cfg != null && cfg.ledgerEnabled()) {
      return true;
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.ledger.disabled"), false);
    return false;
  }

  private static int cmdLedgerRecent(
      final ServerCommandSource src, final Services services, final int limit) {
    if (!ensureLedgerEnabled(src)) {
      return 0;
    }
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY id DESC LIMIT ?";
    return printLedger(src, services, sql, ps -> ps.setInt(1, Math.max(1, Math.min(200, limit))));
  }

  private static int cmdLedgerByPlayer(
      final ServerCommandSource src,
      final Services services,
      final String target,
      final int limit) {
    if (!ensureLedgerEnabled(src)) {
      return 0;
    }
    UUID uuid = tryParseUuid(target);
    if (uuid == null) {
      Players players = services.players();
      PlayerRef ref = players.byName(target).orElse(null);
      if (ref == null) {
        src.sendFeedback(() -> Text.translatable("mincore.err.player.unknown"), false);
        return 0;
      }
      uuid = ref.uuid();
    }

    final UUID u = uuid;
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE from_uuid = ? OR to_uuid = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          byte[] b = uuidToBytes(u);
          ps.setBytes(1, b);
          ps.setBytes(2, b);
          ps.setInt(3, Math.max(1, Math.min(200, limit)));
        });
  }

  private static int cmdLedgerByAddon(
      final ServerCommandSource src,
      final Services services,
      final String addonId,
      final int limit) {
    if (!ensureLedgerEnabled(src)) {
      return 0;
    }
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
            + " idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger WHERE addon_id = ? ORDER BY id DESC LIMIT ?";
    return printLedger(
        src,
        services,
        sql,
        ps -> {
          ps.setString(1, addonId);
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  private static int cmdLedgerByReason(
      final ServerCommandSource src,
      final Services services,
      final String needle,
      final int limit) {
    if (!ensureLedgerEnabled(src)) {
      return 0;
    }
    final String sql =
        "SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq,"
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
      LOG.warn("(mincore) ledger query failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.ledger.error", e.getClass().getSimpleName()), false);
      return 0;
    }

    if (rows.isEmpty()) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.ledger.none"), false);
      return 1;
    }

    ZoneId zone = Timezones.resolve(src, services);
    DateTimeFormatter fmt = LEDGER_TIME.withZone(zone);
    Players players = services.players();
    src.sendFeedback(
        () -> Text.translatable("mincore.cmd.ledger.header", rows.size(), zone.getId()), false);
    for (LedgerRow row : rows) {
      String when = fmt.format(Instant.ofEpochSecond(row.ts()));
      String from = formatPlayer(players, row.from());
      String to = formatPlayer(players, row.to());
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.ledger.line",
                  row.id(),
                  when,
                  row.addon(),
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

  private static int cmdJobsList(final ServerCommandSource src, final Services services) {
    List<Scheduler.JobStatus> jobs = Scheduler.jobs();
    ZoneId zone = Timezones.resolve(src, services);
    DateTimeFormatter fmt = LEDGER_TIME.withZone(zone);
    if (jobs.isEmpty()) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.list.none"), false);
      return 1;
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.list.header", zone.getId()), false);
    for (Scheduler.JobStatus job : jobs) {
      String next = job.nextRun != null ? fmt.format(job.nextRun) : "-";
      String last = job.lastRun != null ? fmt.format(job.lastRun) : "-";
      String error = job.lastError == null ? "" : job.lastError;
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.jobs.list.line",
                  job.name,
                  job.schedule,
                  job.description,
                  next,
                  last,
                  job.running,
                  job.successCount,
                  job.failureCount,
                  error),
          false);
    }
    return 1;
  }

  private static int cmdJobsRun(final ServerCommandSource src, final String job) {
    boolean scheduled = Scheduler.runNow(job);
    if (scheduled) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.run.ok", job), false);
      return 1;
    }
    src.sendFeedback(() -> Text.translatable("mincore.cmd.jobs.run.unknown", job), false);
    return 0;
  }

  private static int cmdBackupNow(final ServerCommandSource src, final Services services) {
    Config cfg = MinCoreMod.config();
    if (cfg == null) {
      src.sendFeedback(() -> Text.translatable("mincore.cmd.backup.fail", "config"), false);
      return 0;
    }
    try {
      BackupExporter.Result result = BackupExporter.exportAll(services, cfg);
      src.sendFeedback(
          () ->
              Text.translatable(
                  "mincore.cmd.backup.ok",
                  result.file().toString(),
                  result.players(),
                  result.attributes(),
                  result.ledger()),
          false);
      return 1;
    } catch (Exception e) {
      LOG.warn("(mincore) /mincore backup now failed", e);
      src.sendFeedback(
          () -> Text.translatable("mincore.cmd.backup.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static String isolationName(int level) {
    return switch (level) {
      case Connection.TRANSACTION_NONE -> "NONE";
      case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
      case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
      case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
      case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
      default -> "UNKNOWN";
    };
  }

  private static boolean allowAdminRateLimit(ServerCommandSource src, String op) {
    String key = src.getEntity() != null ? src.getEntity().getUuid().toString() : src.getName();
    long now = Instant.now().getEpochSecond();
    if (ADMIN_RATE_LIMITER.tryAcquire(key, now)) {
      return true;
    }
    LOG.debug("(mincore) admin {} rate-limited for {}", op, key);
    src.sendFeedback(() -> Text.translatable("mincore.err.cmd.rateLimited"), false);
    return false;
  }

  private static UUID tryParseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (Exception ignored) {
      return null;
    }
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

  private static byte[] uuidToBytes(UUID u) {
    if (u == null) {
      return null;
    }
    byte[] out = new byte[16];
    long msb = u.getMostSignificantBits();
    long lsb = u.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) (msb >>> (8 * (7 - i)));
    }
    for (int i = 0; i < 8; i++) {
      out[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
    }
    return out;
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

  private record LedgerRow(
      long id,
      long ts,
      String addon,
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
