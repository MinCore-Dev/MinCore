/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.holarki.HolarkiMod;
import dev.holarki.api.ErrorCode;
import dev.holarki.core.BackupExporter;
import dev.holarki.core.BackupImporter;
import dev.holarki.core.Config;
import dev.holarki.core.Migrations;
import dev.holarki.core.Services;
import dev.holarki.core.SqlErrorCodes;
import dev.holarki.core.modules.ModuleContext;
import dev.holarki.core.modules.ModuleStateView;
import dev.holarki.util.TokenBucketRateLimiter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admin/diagnostic commands exposed under /holarki (permission level 4). */
public final class AdminCommands {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final TokenBucketRateLimiter ADMIN_RATE_LIMITER =
      new TokenBucketRateLimiter(4, 0.3);
  private static final List<ModuleContext.AdminCommandExtension> EXTENSIONS =
      new CopyOnWriteArrayList<>();

  private AdminCommands() {}

  /** Clears registered command extensions so subsequent registrations start from a clean slate. */
  public static void resetExtensions() {
    EXTENSIONS.clear();
  }

  private static void extend(ModuleContext.AdminCommandExtension extension) {
    Objects.requireNonNull(extension, "extension");
    EXTENSIONS.add(extension);
  }

  /** Returns the registrar used by {@link ModuleContext} to add admin command extensions. */
  public static Consumer<ModuleContext.AdminCommandExtension> registrar() {
    return AdminCommands::extend;
  }

  /**
   * Registers the `/holarki` command tree.
   *
   * @param services service container backing command handlers
   */
  public static void register(final Services services, final ModuleStateView modules) {
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(modules, "modules");
    List<ModuleContext.AdminCommandExtension> extensions =
        new ArrayList<>(modules.adminCommandExtensions());
    resetExtensions();
    for (ModuleContext.AdminCommandExtension extension : extensions) {
      extend(extension);
    }
    CommandRegistrationCallback.EVENT.register(
        (CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) -> {
          LiteralArgumentBuilder<ServerCommandSource> root =
              CommandManager.literal("holarki").requires(src -> src.hasPermissionLevel(4));

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

          for (ModuleContext.AdminCommandExtension extension : EXTENSIONS) {
            extension.attach(root);
          }

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
      src.sendFeedback(() -> Text.translatable("holarki.cmd.db.ping.ok", tookMs), false);
      return 1;
    } catch (Exception e) {
      logAdminFailure("/holarki db ping", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.db.ping.fail", e.getClass().getSimpleName()), false);
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

      Config cfg = HolarkiMod.config();
      String host = cfg != null ? cfg.db().host() : "?";
      int port = cfg != null ? cfg.db().port() : -1;
      boolean tls = cfg != null && cfg.db().tlsEnabled();

      src.sendFeedback(
          () ->
              Text.translatable(
                  "holarki.cmd.db.info.detail",
                  host,
                  port,
                  tls ? "tls" : "plain",
                  product,
                  url,
                  isolation),
          false);
      return 1;
    } catch (Exception e) {
      logAdminFailure("/holarki db info", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.db.info.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
  }

  private static int cmdDiag(final ServerCommandSource src, final Services services) {
    if (!allowAdminRateLimit(src, "diag")) {
      return 0;
    }
    boolean ok = true;
    try (Connection c = services.database().borrowConnection()) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.diag.db.ok"), false);
      ok &= reportSchemaVersion(src, c);
    } catch (Exception e) {
      ok = false;
      logAdminFailure("/holarki diag", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.diag.db.fail", e.getClass().getSimpleName()), false);
    }

    boolean lock = services.database().tryAdvisoryLock("holarki_diag");
    if (lock) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.diag.lock.ok"), false);
      services.database().releaseAdvisoryLock("holarki_diag");
    } else {
      ok = false;
      src.sendFeedback(() -> Text.translatable("holarki.cmd.diag.lock.fail"), false);
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
            () -> Text.translatable("holarki.cmd.migrate.check.ok", current, target), false);
        return 1;
      }
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.migrate.check.pending", current, target), false);
      return 0;
    } catch (SQLException e) {
      logAdminFailure("/holarki migrate --check", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.migrate.check.fail", e.getClass().getSimpleName()),
          false);
      return 0;
    }
  }

  private static int cmdMigrateApply(final ServerCommandSource src, final Services services) {
    if (!services.database().tryAdvisoryLock("holarki_migrate")) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.migrate.locked"), false);
      return 0;
    }
    try {
      Migrations.apply(services);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.migrate.apply.ok", Migrations.currentVersion()),
          false);
      return 1;
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause instanceof SQLException sql) {
        logAdminFailure("/holarki migrate --apply", sql);
        String detail =
            String.format(
                "%s (SQLState=%s vendor=%s)",
                sql.getMessage(), sql.getSQLState(), sql.getErrorCode());
        src.sendFeedback(() -> Text.translatable("holarki.cmd.migrate.apply.fail", detail), false);
      } else {
        logAdminFailure("/holarki migrate --apply", e);
        src.sendFeedback(
            () -> Text.translatable("holarki.cmd.migrate.apply.fail", e.getMessage()), false);
      }
      return 0;
    } finally {
      services.database().releaseAdvisoryLock("holarki_migrate");
    }
  }

  private static int cmdExportAll(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    Config cfg = HolarkiMod.config();
    if (cfg == null) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.export.fail", "config"), false);
      return 0;
    }
    final ExportOptions opts;
    try {
      opts = ExportOptions.parse(rawOptions);
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.export.usage", e.getMessage()), false);
      return 0;
    }
    final Path outDir = opts.outDir != null ? opts.outDir : Path.of(cfg.jobs().backup().outDir());
    src.sendFeedback(
        () -> Text.translatable("holarki.cmd.export.queued", outDir.toString()), false);
    final MinecraftServer server = src.getServer();
    try {
      services.scheduler()
          .execute(
              () -> {
                try {
                  BackupExporter.Result result =
                      BackupExporter.exportAll(services, cfg, outDir, opts.gzipOverride);
                  runOnServerThread(
                      server,
                      () ->
                          src.sendFeedback(
                              () ->
                                  Text.translatable(
                                      "holarki.cmd.export.ok",
                                      result.file().toString(),
                                      result.players(),
                                      result.attributes(),
                                      result.eventSeq(),
                                      result.ledger()),
                              false),
                      "export success feedback");
                } catch (Exception e) {
                  logAdminFailure("/holarki export", e);
                  runOnServerThread(
                      server,
                      () ->
                          src.sendFeedback(
                              () ->
                                  Text.translatable(
                                      "holarki.cmd.export.fail", e.getClass().getSimpleName()),
                              false),
                      "export failure feedback");
                }
              });
    } catch (RuntimeException e) {
      logAdminFailure("/holarki export", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.export.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
    return 1;
  }

  private static int cmdRestore(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    final RestoreOptions opts;
    try {
      opts = RestoreOptions.parse(rawOptions);
      if (opts.from == null) {
        throw new IllegalArgumentException("--from <dir> required");
      }
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.restore.usage", e.getMessage()), false);
      return 0;
    }
    src.sendFeedback(
        () ->
            Text.translatable(
                "holarki.cmd.restore.queued",
                opts.mode.name().toLowerCase(Locale.ROOT),
                opts.from.toString()),
        false);
    final MinecraftServer server = src.getServer();
    try {
      services.scheduler()
          .execute(
              () -> {
                try {
                  BackupImporter.Result result =
                      BackupImporter.restore(
                          services,
                          opts.from,
                          opts.mode,
                          opts.strategy,
                          opts.overwrite,
                          opts.skipFkChecks);
                  runOnServerThread(
                      server,
                      () ->
                          src.sendFeedback(
                              () ->
                                  Text.translatable(
                                      "holarki.cmd.restore.ok",
                                      result.source().toString(),
                                      result.players(),
                                      result.attributes(),
                                      result.eventSeq(),
                                      result.ledger()),
                              false),
                      "restore success feedback");
                } catch (IOException | SQLException e) {
                  logAdminFailure("/holarki restore", e);
                  runOnServerThread(
                      server,
                      () ->
                          src.sendFeedback(
                              () ->
                                  Text.translatable(
                                      "holarki.cmd.restore.fail", e.getMessage()),
                              false),
                      "restore failure feedback");
                } catch (Exception e) {
                  logAdminFailure("/holarki restore", e);
                  runOnServerThread(
                      server,
                      () ->
                          src.sendFeedback(
                              () ->
                                  Text.translatable(
                                      "holarki.cmd.restore.fail", e.getClass().getSimpleName()),
                              false),
                      "restore failure feedback");
                }
              });
    } catch (RuntimeException e) {
      logAdminFailure("/holarki restore", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.restore.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
    return 1;
  }

  private static int cmdDoctor(
      final ServerCommandSource src, final Services services, final String rawOptions) {
    DoctorOptions opts;
    try {
      opts = DoctorOptions.parse(rawOptions);
    } catch (IllegalArgumentException e) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.usage", e.getMessage()), false);
      return 0;
    }
    if (!opts.hasAny()) {
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.doctor.usage", "specify at least one flag"), false);
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
      logAdminFailure("/holarki doctor", e);
      src.sendFeedback(
          () -> Text.translatable("holarki.cmd.doctor.fail", e.getClass().getSimpleName()), false);
      return 0;
    }
    return ok ? 1 : 0;
  }

  private static boolean reportSchemaVersion(ServerCommandSource src, Connection c) {
    try (PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM core_schema_version");
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        long version = rs.getLong(1);
        src.sendFeedback(() -> Text.translatable("holarki.cmd.diag.schema", version), false);
        return true;
      }
    } catch (SQLException e) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.diag.schemaMissing"), false);
    }
    return false;
  }

  private static boolean doctorCounts(ServerCommandSource src, Connection c) throws SQLException {
    String sql =
        "SELECT (SELECT COUNT(*) FROM players) AS players,"
            + " (SELECT COUNT(*) FROM player_attributes) AS attrs,"
            + " (SELECT COUNT(*) FROM player_event_seq) AS seq,"
            + " (SELECT COUNT(*) FROM core_ledger) AS ledger,"
            + " (SELECT COUNT(*) FROM core_requests) AS requests";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        final long players = rs.getLong("players");
        final long attrs = rs.getLong("attrs");
        final long seq = rs.getLong("seq");
        final long ledger = rs.getLong("ledger");
        final long requests = rs.getLong("requests");
        src.sendFeedback(
            () ->
                Text.translatable(
                    "holarki.cmd.doctor.counts", players, attrs, seq, ledger, requests),
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
        src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.fk.ok"), false);
        return true;
      }
      src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.fk.fail", count), false);
      return false;
    }
  }

  private static void runOnServerThread(
      final MinecraftServer server, final Runnable callback, final String actionDescription) {
    if (server != null) {
      server.execute(callback);
    } else {
      LOG.warn(
          "(holarki) admin command feedback dropped ({}): server reference unavailable",
          actionDescription);
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
      src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.orphans.ok"), false);
      return true;
    }
    src.sendFeedback(
        () -> Text.translatable("holarki.cmd.doctor.orphans.fail", missingFrom, missingTo, total),
        false);
    return false;
  }

  private static boolean doctorAnalyze(ServerCommandSource src, Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("ANALYZE TABLE players");
      st.execute("ANALYZE TABLE player_attributes");
      st.execute("ANALYZE TABLE core_ledger");
    }
    src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.analyze.ok"), false);
    return true;
  }

  private static boolean doctorLocks(ServerCommandSource src, Services services) {
    if (services.database().tryAdvisoryLock("holarki_doctor_lock")) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.locks.ok"), false);
      services.database().releaseAdvisoryLock("holarki_doctor_lock");
      return true;
    }
    src.sendFeedback(() -> Text.translatable("holarki.cmd.doctor.locks.fail"), false);
    return false;
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
    if (ADMIN_RATE_LIMITER.tryAcquire(key)) {
      return true;
    }
    LOG.debug("(holarki) admin {} rate-limited for {}", op, key);
    src.sendFeedback(() -> Text.translatable("holarki.err.cmd.rateLimited"), false);
    return false;
  }

  public static void logAdminFailure(String op, Throwable error) {
    ErrorCode code = classifyError(error);
    if (error instanceof SQLException sql) {
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          op,
          sql.getMessage(),
          sql.getSQLState(),
          sql.getErrorCode(),
          sql);
    } else {
      LOG.warn("(holarki) code={} op={} message={}", code, op, error.getMessage(), error);
    }
  }

  private static ErrorCode classifyError(Throwable error) {
    if (error instanceof SQLException sql) {
      return SqlErrorCodes.classify(sql);
    }
    return ErrorCode.CONNECTION_LOST;
  }

}
