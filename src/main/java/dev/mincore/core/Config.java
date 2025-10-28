/* MinCore © 2025 — MIT */
package dev.mincore.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime configuration loaded from {@code config/mincore.json5}.
 *
 * <p>The loader is spec-compliant:
 *
 * <ul>
 *   <li>Writes a commented template on first boot.
 *   <li>Emits a {@code mincore.json5.example} snapshot for ops tooling.
 *   <li>Supports environment overrides for the DB connection ({@code MINCORE_DB_*}).
 *   <li>Parses module toggles plus i18n, timezone, scheduler, and logging blocks.
 * </ul>
 */
public final class Config {

  private static final String TEMPLATE =
      """
      // MinCore v1.0.0 configuration (JSON5 with comments)
      // Drop into config/mincore.json5. Environment overrides: MINCORE_DB_HOST|PORT|DATABASE|USER|PASSWORD.
      {
        modules: {
          ledger: {
            enabled: true,
            retentionDays: 0,
            file: {
              enabled: false,
              path: "./logs/mincore-ledger.jsonl"
            }
          },
          scheduler: {
            enabled: true,
            jobs: {
              backup: {
                enabled: true,
                schedule: "0 45 4 * * *",
                outDir: "./backups/mincore",
                onMissed: "runAtNextStartup",
                gzip: true,
                prune: { keepDays: 14, keepMax: 60 }
              },
              cleanup: {
                idempotencySweep: {
                  enabled: true,
                  schedule: "0 30 4 * * *",
                  retentionDays: 30,
                  batchLimit: 5000
                }
              }
            }
          },
          timezone: {
            enabled: true,
            autoDetect: {
              enabled: false,
              database: "./config/mincore.geoip.mmdb"
            }
          },
          playtime: { enabled: true }
        },
        core: {
          db: {
            host: "127.0.0.1",
            port: 3306,
            database: "mincore",
            user: "mincore",
            password: "change-me",
            tls: { enabled: false },
            session: { forceUtc: true },
            pool: {
              maxPoolSize: 10,
              minimumIdle: 2,
              connectionTimeoutMs: 10000,
              idleTimeoutMs: 600000,
              maxLifetimeMs: 1700000,
              startupAttempts: 3
            }
          },
          runtime: { reconnectEveryS: 10 },
          time: {
            display: {
              defaultZone: "UTC",
              allowPlayerOverride: false
            }
          },
          i18n: {
            defaultLocale: "en_US",
            enabledLocales: [ "en_US" ],
            fallbackLocale: "en_US"
          },
          log: {
            json: false,
            slowQueryMs: 250,
            level: "INFO"
          }
        }
      }
      """;

  private final Db db;
  private final Runtime runtime;
  private final Time time;
  private final I18n i18n;
  private final Modules modules;
  private final Log log;

  private Config(Db db, Runtime runtime, Time time, I18n i18n, Modules modules, Log log) {
    this.db = db;
    this.runtime = runtime;
    this.time = time;
    this.i18n = i18n;
    this.modules = modules;
    this.log = log;
  }

  /**
   * Database connection block.
   *
   * @return database settings
   */
  public Db db() {
    return db;
  }

  /**
   * Runtime behavior (reconnect cadence, etc.).
   *
   * @return runtime configuration values
   */
  public Runtime runtime() {
    return runtime;
  }

  /**
   * Timezone display configuration.
   *
   * @return timezone display preferences
   */
  public Time time() {
    return time;
  }

  /**
   * Localization configuration.
   *
   * @return i18n settings
   */
  public I18n i18n() {
    return i18n;
  }

  /**
   * Module configuration.
   *
   * @return structured module toggles and settings
   */
  public Modules modules() {
    return modules;
  }

  /**
   * Ledger configuration.
   *
   * @return ledger configuration block
   */
  public Ledger ledger() {
    return modules.ledger();
  }

  /**
   * Scheduled job configuration.
   *
   * @return job scheduling block
   */
  public Jobs jobs() {
    return modules.scheduler().jobs();
  }

  /**
   * Logging configuration.
   *
   * @return logging configuration block
   */
  public Log log() {
    return log;
  }

  /**
   * Convenience accessor: whether the ledger is enabled.
   *
   * @return {@code true} if the ledger subsystem should initialize
   */
  public boolean ledgerEnabled() {
    return modules.ledger().enabled();
  }

  /**
   * Loads configuration, writing a default file if it does not exist and always refreshing the
   * commented example alongside it.
   *
   * @param path config path
   * @return parsed config
   */
  public static Config loadOrWriteDefault(Path path) {
    try {
      Path configDir = path.getParent();
      Path exampleDir = configDir != null ? configDir : Path.of(".");
      ConfigTemplateWriter.writeExample(exampleDir.resolve("mincore.json5.example"), TEMPLATE);

      if (!Files.exists(path)) {
        if (configDir != null) {
          Files.createDirectories(configDir);
        }
        Files.writeString(path, TEMPLATE, StandardCharsets.UTF_8);
      }

      String raw = Files.readString(path, StandardCharsets.UTF_8);
      String json = stripJson5(raw);
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject core = optObject(root, "core");
      if (core == null) {
        throw new IllegalStateException("config missing core{} block");
      }

      Db db = parseDb(optObject(core, "db"));
      Runtime runtime = parseRuntime(optObject(core, "runtime"));
      Time time = parseTime(optObject(core, "time"));
      Modules modules =
          parseModules(optObject(root, "modules"), time.display().autoDetect());
      time =
          new Time(
              new Display(
                  time.display().defaultZone(),
                  time.display().allowPlayerOverride(),
                  modules.timezone().autoDetect().enabled()));
      I18n i18n = parseI18n(optObject(core, "i18n"));
      Log log = parseLog(optObject(core, "log"));

      Config config = new Config(db, runtime, time, i18n, modules, log);
      validate(config);
      return config;
    } catch (IOException e) {
      throw new RuntimeException("Failed to read config: " + path, e);
    }
  }

  private static String stripJson5(String raw) {
    return raw.replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("(?m)//.*$", "")
        .replaceAll(",(?=\\s*[}\\]])", "");
  }

  private static Db parseDb(JsonObject db) {
    if (db == null) {
      throw new IllegalStateException("config missing core.db{}");
    }
    String envHost = System.getenv("MINCORE_DB_HOST");
    String envPort = System.getenv("MINCORE_DB_PORT");
    String envDatabase = System.getenv("MINCORE_DB_DATABASE");
    String envUser = System.getenv("MINCORE_DB_USER");
    String envPassword = System.getenv("MINCORE_DB_PASSWORD");

    String host = envHost != null ? envHost : db.get("host").getAsString();
    int port = envPort != null ? Integer.parseInt(envPort) : db.get("port").getAsInt();
    String database = envDatabase != null ? envDatabase : db.get("database").getAsString();
    String user = envUser != null ? envUser : db.get("user").getAsString();
    String password = envPassword != null ? envPassword : db.get("password").getAsString();

    JsonObject tlsObj = optObject(db, "tls");
    boolean tls = tlsObj != null && optBoolean(tlsObj, "enabled", false);

    JsonObject sessionObj = optObject(db, "session");
    boolean forceUtc = sessionObj == null || optBoolean(sessionObj, "forceUtc", true);

    JsonObject poolObj = optObject(db, "pool");
    int maxPool = optInt(poolObj, "maxPoolSize", 10);
    int minIdle = optInt(poolObj, "minimumIdle", 2);
    long connTimeout = optLong(poolObj, "connectionTimeoutMs", 10_000L);
    long idleTimeout = optLong(poolObj, "idleTimeoutMs", 600_000L);
    long maxLifetime = optLong(poolObj, "maxLifetimeMs", 1_700_000L);
    int startupAttempts = optInt(poolObj, "startupAttempts", 3);

    return new Db(
        host,
        port,
        database,
        user,
        password,
        tls,
        forceUtc,
        new Pool(maxPool, minIdle, connTimeout, idleTimeout, maxLifetime, startupAttempts));
  }

  private static Runtime parseRuntime(JsonObject runtime) {
    int reconnectEvery = runtime != null ? optInt(runtime, "reconnectEveryS", 10) : 10;
    return new Runtime(reconnectEvery);
  }

  private static Time parseTime(JsonObject time) {
    JsonObject displayObj = time != null ? optObject(time, "display") : null;
    String defaultZone = displayObj != null ? optString(displayObj, "defaultZone", "UTC") : "UTC";
    boolean allowOverride =
        displayObj != null && optBoolean(displayObj, "allowPlayerOverride", false);
    boolean autoDetect = displayObj != null && optBoolean(displayObj, "autoDetect", false);

    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(defaultZone);
    } catch (Exception e) {
      throw new IllegalStateException(
          "core.time.display.defaultZone is invalid: " + defaultZone, e);
    }
    return new Time(new Display(zoneId, allowOverride, autoDetect));
  }

  private static Modules parseModules(JsonObject modules, boolean defaultAutoDetect) {
    Ledger ledger = parseLedger(optObject(modules, "ledger"));
    SchedulerModule scheduler = parseScheduler(optObject(modules, "scheduler"));
    TimezoneModule timezone = parseTimezone(optObject(modules, "timezone"), defaultAutoDetect);
    PlaytimeModule playtime = parsePlaytime(optObject(modules, "playtime"));
    return new Modules(ledger, scheduler, timezone, playtime);
  }

  private static SchedulerModule parseScheduler(JsonObject scheduler) {
    boolean enabled = scheduler == null || optBoolean(scheduler, "enabled", true);
    JsonObject jobsObj = scheduler != null ? optObject(scheduler, "jobs") : null;
    Jobs jobs = parseJobs(jobsObj);
    return new SchedulerModule(enabled, jobs);
  }

  private static TimezoneModule parseTimezone(JsonObject timezone, boolean defaultAutoDetect) {
    boolean enabled = timezone == null || optBoolean(timezone, "enabled", true);
    JsonObject autoObj = timezone != null ? optObject(timezone, "autoDetect") : null;
    boolean autoEnabled =
        autoObj != null ? optBoolean(autoObj, "enabled", defaultAutoDetect) : defaultAutoDetect;
    String database =
        autoObj != null
            ? optString(autoObj, "database", "./config/mincore.geoip.mmdb")
            : "./config/mincore.geoip.mmdb";
    return new TimezoneModule(enabled, new AutoDetect(autoEnabled, database));
  }

  private static PlaytimeModule parsePlaytime(JsonObject playtime) {
    boolean enabled = playtime == null || optBoolean(playtime, "enabled", true);
    return new PlaytimeModule(enabled);
  }

  private static I18n parseI18n(JsonObject i18n) {
    if (i18n == null) {
      return new I18n(
          Locale.forLanguageTag("en-US"), List.of("en_US"), Locale.forLanguageTag("en-US"));
    }
    String def = optString(i18n, "defaultLocale", "en_US");
    String fallback = optString(i18n, "fallbackLocale", def);
    List<String> enabled = new ArrayList<>();
    if (i18n.has("enabledLocales") && i18n.get("enabledLocales").isJsonArray()) {
      for (JsonElement el : i18n.get("enabledLocales").getAsJsonArray()) {
        enabled.add(el.getAsString());
      }
    } else {
      enabled.add(def);
    }
    if (enabled.isEmpty()) enabled.add(def);
    return new I18n(locale(def), List.copyOf(enabled), locale(fallback));
  }

  private static Ledger parseLedger(JsonObject ledger) {
    if (ledger == null) {
      return new Ledger(false, 0, new JsonlMirror(false, "./logs/mincore-ledger.jsonl"));
    }
    boolean enabled = optBoolean(ledger, "enabled", false);
    int retention = optInt(ledger, "retentionDays", 0);
    JsonObject file = optObject(ledger, "file");
    boolean jsonl = file != null && optBoolean(file, "enabled", false);
    String path =
        file != null
            ? optString(file, "path", "./logs/mincore-ledger.jsonl")
            : "./logs/mincore-ledger.jsonl";
    return new Ledger(enabled, retention, new JsonlMirror(jsonl, path));
  }

  private static Jobs parseJobs(JsonObject jobs) {
    if (jobs == null) {
      return new Jobs(
          new Backup(
              true, "0 45 4 * * *", "./backups/mincore", OnMissed.RUN_AT_NEXT_STARTUP, true,
              new Prune(14, 60)),
          new Cleanup(new IdempotencySweep(true, "0 30 4 * * *", 30, 5000)));
    }
    JsonObject backupObj = optObject(jobs, "backup");
    Backup backup =
        new Backup(
            backupObj != null && optBoolean(backupObj, "enabled", false),
            backupObj != null ? optString(backupObj, "schedule", "0 45 4 * * *") : "0 45 4 * * *",
            backupObj != null
                ? optString(backupObj, "outDir", "./backups/mincore")
                : "./backups/mincore",
            OnMissed.from(optString(backupObj, "onMissed", "skip")),
            backupObj == null || optBoolean(backupObj, "gzip", true),
            parsePrune(backupObj));

    JsonObject cleanupObj = optObject(jobs, "cleanup");
    JsonObject sweepObj = cleanupObj != null ? optObject(cleanupObj, "idempotencySweep") : null;
    IdempotencySweep sweep =
        new IdempotencySweep(
            sweepObj == null || optBoolean(sweepObj, "enabled", true),
            sweepObj != null ? optString(sweepObj, "schedule", "0 30 4 * * *") : "0 30 4 * * *",
            sweepObj != null ? optInt(sweepObj, "retentionDays", 30) : 30,
            sweepObj != null ? optInt(sweepObj, "batchLimit", 5000) : 5000);
    return new Jobs(backup, new Cleanup(sweep));
  }

  private static Prune parsePrune(JsonObject backupObj) {
    JsonObject prune = backupObj != null ? optObject(backupObj, "prune") : null;
    int keepDays = prune != null ? optInt(prune, "keepDays", 14) : 14;
    int keepMax = prune != null ? optInt(prune, "keepMax", 60) : 60;
    return new Prune(keepDays, keepMax);
  }

  private static Log parseLog(JsonObject log) {
    if (log == null) {
      return new Log(false, 250L, "INFO");
    }
    boolean json = optBoolean(log, "json", false);
    long slow = optLong(log, "slowQueryMs", 250L);
    String level = optString(log, "level", "INFO");
    return new Log(json, slow, level);
  }

  private static JsonObject optObject(JsonObject parent, String key) {
    return parent != null && parent.has(key) && parent.get(key).isJsonObject()
        ? parent.getAsJsonObject(key)
        : null;
  }

  private static boolean optBoolean(JsonObject obj, String key, boolean def) {
    return obj != null && obj.has(key) ? obj.get(key).getAsBoolean() : def;
  }

  private static int optInt(JsonObject obj, String key, int def) {
    return obj != null && obj.has(key) ? obj.get(key).getAsInt() : def;
  }

  private static long optLong(JsonObject obj, String key, long def) {
    return obj != null && obj.has(key) ? obj.get(key).getAsLong() : def;
  }

  private static String optString(JsonObject obj, String key, String def) {
    return obj != null && obj.has(key) ? obj.get(key).getAsString() : def;
  }

  private static Locale locale(String code) {
    Objects.requireNonNull(code, "locale");
    return Locale.forLanguageTag(code.replace('_', '-'));
  }

  private static void validate(Config cfg) {
    validateDb(cfg.db());
    validateRuntime(cfg.runtime());
    validateTime(cfg.time());
    validateI18n(cfg.i18n());
    validateModules(cfg.modules(), cfg.time());
    validateLog(cfg.log());
  }

  private static void validateDb(Db db) {
    requireNonBlank(db.host(), "core.db.host");
    requireNonBlank(db.database(), "core.db.database");
    requireNonBlank(db.user(), "core.db.user");
    requireNonBlank(db.password(), "core.db.password");
    if (db.port() <= 0 || db.port() > 65535) {
      throw new IllegalStateException("core.db.port must be between 1 and 65535");
    }
    if (db.host().contains(" ")) {
      throw new IllegalStateException("core.db.host must not contain spaces");
    }
    if (!db.database().matches("[A-Za-z0-9_]+")) {
      throw new IllegalStateException("core.db.database must match [A-Za-z0-9_]+");
    }
    int maxPool = db.pool().maxPoolSize();
    if (maxPool < 1 || maxPool > 50) {
      throw new IllegalStateException("core.db.pool.maxPoolSize must be between 1 and 50");
    }
    int minIdle = db.pool().minimumIdle();
    if (minIdle < 0 || minIdle > maxPool) {
      throw new IllegalStateException("core.db.pool.minimumIdle must be between 0 and maxPoolSize");
    }
    long connectionTimeout = db.pool().connectionTimeoutMs();
    if (connectionTimeout < 1_000 || connectionTimeout > 120_000) {
      throw new IllegalStateException(
          "core.db.pool.connectionTimeoutMs must be between 1000 and 120000");
    }
    long idleTimeout = db.pool().idleTimeoutMs();
    if (idleTimeout < 10_000 || idleTimeout > 3_600_000) {
      throw new IllegalStateException(
          "core.db.pool.idleTimeoutMs must be between 10000 and 3600000");
    }
    long maxLifetime = db.pool().maxLifetimeMs();
    if (maxLifetime < 30_000L || maxLifetime > 3_600_000L) {
      throw new IllegalStateException(
          "core.db.pool.maxLifetimeMs must be between 30000 and 3600000");
    }
    int attempts = db.pool().startupAttempts();
    if (attempts < 1 || attempts > 10) {
      throw new IllegalStateException("core.db.pool.startupAttempts must be between 1 and 10");
    }
    if (idleTimeout >= maxLifetime) {
      throw new IllegalStateException("core.db.pool.idleTimeoutMs must be less than maxLifetimeMs");
    }
    if (db.pool().connectionTimeoutMs() >= maxLifetime) {
      throw new IllegalStateException(
          "core.db.pool.connectionTimeoutMs must be less than maxLifetimeMs");
    }
  }

  private static void validateRuntime(Runtime runtime) {
    int reconnect = runtime.reconnectEveryS();
    if (reconnect < 5 || reconnect > 300) {
      throw new IllegalStateException(
          "core.runtime.reconnectEveryS must be between 5 and 300 seconds");
    }
  }

  private static void validateTime(Time time) {
    if (time == null || time.display() == null) {
      throw new IllegalStateException("core.time.display must be specified");
    }
    if (time.display().defaultZone() == null) {
      throw new IllegalStateException("core.time.display.defaultZone must be a valid ZoneId");
    }
    if (time.display().autoDetect() && !time.display().allowPlayerOverride()) {
      throw new IllegalStateException(
          "core.time.display.autoDetect requires allowPlayerOverride=true");
    }
  }

  private static void validateI18n(I18n i18n) {
    if (i18n == null) {
      throw new IllegalStateException("core.i18n block missing");
    }
    if (i18n.enabledLocales().isEmpty()) {
      throw new IllegalStateException("core.i18n.enabledLocales must include at least one entry");
    }
    for (String locale : i18n.enabledLocales()) {
      requireNonBlank(locale, "core.i18n.enabledLocales entry");
    }
    requireNonBlank(i18n.defaultLocale().toLanguageTag(), "core.i18n.defaultLocale");
    requireNonBlank(i18n.fallbackLocale().toLanguageTag(), "core.i18n.fallbackLocale");
    String defaultCode = i18n.defaultLocale().toLanguageTag().replace('-', '_');
    if (i18n.enabledLocales().stream().noneMatch(l -> l.equalsIgnoreCase(defaultCode))) {
      throw new IllegalStateException("core.i18n.enabledLocales must include defaultLocale");
    }
    String fallbackCode = i18n.fallbackLocale().toLanguageTag().replace('-', '_');
    if (i18n.enabledLocales().stream().noneMatch(l -> l.equalsIgnoreCase(fallbackCode))) {
      throw new IllegalStateException("core.i18n.enabledLocales must include fallbackLocale");
    }
  }

  private static void validateModules(Modules modules, Time time) {
    if (modules == null) {
      throw new IllegalStateException("modules block missing");
    }
    if (modules.ledger() == null) {
      throw new IllegalStateException("modules.ledger block missing");
    }
    if (modules.scheduler() == null) {
      throw new IllegalStateException("modules.scheduler block missing");
    }
    if (modules.timezone() == null) {
      throw new IllegalStateException("modules.timezone block missing");
    }
    if (modules.timezone().autoDetect() == null) {
      throw new IllegalStateException("modules.timezone.autoDetect block missing");
    }
    if (modules.playtime() == null) {
      throw new IllegalStateException("modules.playtime block missing");
    }
    validateLedger(modules.ledger());
    validateJobs(modules.scheduler().jobs());
    if (!modules.scheduler().enabled()) {
      boolean backupEnabled = modules.scheduler().jobs().backup().enabled();
      boolean cleanupEnabled = modules.scheduler().jobs().cleanup().idempotencySweep().enabled();
      if (backupEnabled || cleanupEnabled) {
        throw new IllegalStateException(
            "modules.scheduler.enabled=false requires all jobs to be disabled");
      }
    }
    if (modules.timezone().autoDetect().enabled()) {
      if (!modules.timezone().enabled()) {
        throw new IllegalStateException(
            "modules.timezone.autoDetect.enabled requires modules.timezone.enabled=true");
      }
      requireNonBlank(
          modules.timezone().autoDetect().databasePath(), "modules.timezone.autoDetect.database");
      ensureValidPath(
          modules.timezone().autoDetect().databasePath(), "modules.timezone.autoDetect.database");
    }
    if (!modules.timezone().enabled() && time.display().autoDetect()) {
      throw new IllegalStateException(
          "modules.timezone.enabled=false requires core.time.display.autoDetect=false");
    }
  }

  private static void validateLedger(Ledger ledger) {
    if (ledger.retentionDays() < 0) {
      throw new IllegalStateException("core.ledger.retentionDays must be >= 0");
    }
    if (ledger.jsonlMirror().enabled()) {
      requireNonBlank(ledger.jsonlMirror().path(), "core.ledger.file.path");
      ensureValidPath(ledger.jsonlMirror().path(), "core.ledger.file.path");
    }
  }

  private static void validateJobs(Jobs jobs) {
    requireNonBlank(jobs.backup().schedule(), "core.jobs.backup.schedule");
    requireNonBlank(jobs.backup().outDir(), "core.jobs.backup.outDir");
    ensureValidPath(jobs.backup().outDir(), "core.jobs.backup.outDir");
    validateCron(jobs.backup().schedule(), "core.jobs.backup.schedule");
    if (jobs.backup().prune().keepDays() < 0) {
      throw new IllegalStateException("core.jobs.backup.prune.keepDays must be >= 0");
    }
    if (jobs.backup().prune().keepMax() < 1) {
      throw new IllegalStateException("core.jobs.backup.prune.keepMax must be >= 1");
    }
    if (jobs.backup().prune().keepDays() > 3650) {
      throw new IllegalStateException("core.jobs.backup.prune.keepDays must be <= 3650");
    }
    if (jobs.backup().prune().keepMax() > 1000) {
      throw new IllegalStateException("core.jobs.backup.prune.keepMax must be <= 1000");
    }
    if (jobs.cleanup().idempotencySweep().retentionDays() < 0) {
      throw new IllegalStateException(
          "core.jobs.cleanup.idempotencySweep.retentionDays must be >= 0");
    }
    if (jobs.cleanup().idempotencySweep().batchLimit() < 1) {
      throw new IllegalStateException("core.jobs.cleanup.idempotencySweep.batchLimit must be >= 1");
    }
    validateCron(
        jobs.cleanup().idempotencySweep().schedule(),
        "core.jobs.cleanup.idempotencySweep.schedule");
  }

  private static void validateLog(Log log) {
    if (log.slowQueryMs() < 0) {
      throw new IllegalStateException("core.log.slowQueryMs must be >= 0");
    }
    requireNonBlank(log.level(), "core.log.level");
    String normalized = log.level().toUpperCase(Locale.ROOT);
    if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(normalized)) {
      throw new IllegalStateException("core.log.level must be TRACE, DEBUG, INFO, WARN, or ERROR");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(field + " must be provided");
    }
  }

  private static void validateCron(String schedule, String field) {
    String[] parts = schedule.trim().split("\\s+");
    if (parts.length != 6) {
      throw new IllegalStateException(field + " must be a 6-field cron expression");
    }
  }

  private static void ensureValidPath(String value, String field) {
    try {
      Path.of(value);
    } catch (Exception e) {
      throw new IllegalStateException(field + " must be a valid file system path", e);
    }
  }

  /**
   * Database settings parsed from {@code core.db}.
   *
   * @param host hostname or IP for the MariaDB/MySQL server
   * @param port TCP port for the database service
   * @param database schema name to use when connecting
   * @param user database user with least-privilege grants
   * @param password password for the configured {@code user}
   * @param tlsEnabled whether to request TLS/SSL when connecting
   * @param forceUtc whether to issue {@code SET time_zone='+00:00'} per connection
   * @param pool pool tuning overrides applied to HikariCP
   */
  public record Db(
      String host,
      int port,
      String database,
      String user,
      String password,
      boolean tlsEnabled,
      boolean forceUtc,
      Pool pool) {

    /**
     * Fully formed JDBC URL (MariaDB tuned for UTF-8 + UTC).
     *
     * @return JDBC URL string for MariaDB connections
     */
    public String jdbcUrl() {
      StringBuilder url =
          new StringBuilder("jdbc:mariadb://")
              .append(host)
              .append(':')
              .append(port)
              .append('/')
              .append(database)
              .append("?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=UTC");
      if (tlsEnabled) {
        url.append(
            "&useSsl=true&useSSL=true&requireSSL=true&sslMode=VERIFY_IDENTITY&trustServerCertificate=false");
      } else {
        url.append("&useSsl=false&useSSL=false");
      }
      return url.toString();
    }
  }

  /**
   * Connection pool tuning.
   *
   * @param maxPoolSize maximum number of pooled connections
   * @param minimumIdle minimum number of idle connections to retain
   * @param connectionTimeoutMs wait time when borrowing a connection
   * @param idleTimeoutMs idle connection eviction threshold
   * @param maxLifetimeMs maximum lifetime of each connection
   * @param startupAttempts retry count when initializing the pool
   */
  public record Pool(
      int maxPoolSize,
      int minimumIdle,
      long connectionTimeoutMs,
      long idleTimeoutMs,
      long maxLifetimeMs,
      int startupAttempts) {}

  /**
   * Runtime reconnect cadence.
   *
   * @param reconnectEveryS number of seconds between degraded-mode reconnect attempts
   */
  public record Runtime(int reconnectEveryS) {}

  /**
   * Display timezone handling.
   *
   * @param display nested block describing defaults and overrides
   */
  public record Time(Display display) {}

  /**
   * Player-visible timezone preferences.
   *
   * @param defaultZone server-wide default display zone
   * @param allowPlayerOverride whether players may store their own timezone
   * @param autoDetect whether the server attempts owner-only auto-detection
   */
  public record Display(ZoneId defaultZone, boolean allowPlayerOverride, boolean autoDetect) {}

  /**
   * Localization configuration.
   *
   * @param defaultLocale locale used when none is specified by players
   * @param enabledLocales locales bundled with the mod
   * @param fallbackLocale locale used when a translation key is missing
   */
  public record I18n(Locale defaultLocale, List<String> enabledLocales, Locale fallbackLocale) {}

  /**
   * Module toggles and advanced settings.
   *
   * @param ledger ledger subsystem configuration
   * @param scheduler scheduler module controlling job wiring
   * @param timezone timezone command + auto-detection controls
   * @param playtime playtime command toggle
   */
  public record Modules(
      Ledger ledger, SchedulerModule scheduler, TimezoneModule timezone, PlaytimeModule playtime) {}

  /**
   * Scheduler module wrapper.
   *
   * @param enabled whether background jobs should be scheduled
   * @param jobs nested job configuration
   */
  public record SchedulerModule(boolean enabled, Jobs jobs) {}

  /**
   * Timezone module options.
   *
   * @param enabled whether timezone commands should register
   * @param autoDetect optional owner-controlled auto-detection settings
   */
  public record TimezoneModule(boolean enabled, AutoDetect autoDetect) {}

  /**
   * Timezone auto-detection settings.
   *
   * @param enabled whether lookups should be attempted on player join
   * @param databasePath path to the MaxMind GeoIP database
   */
  public record AutoDetect(boolean enabled, String databasePath) {}

  /**
   * Playtime module toggle.
   *
   * @param enabled whether the playtime command should register
   */
  public record PlaytimeModule(boolean enabled) {}

  /**
   * Ledger options.
   *
   * @param enabled whether the ledger subsystem should persist entries
   * @param retentionDays optional retention policy for TTL cleanup
   * @param jsonlMirror configuration for the JSONL mirror writer
   */
  public record Ledger(boolean enabled, int retentionDays, JsonlMirror jsonlMirror) {}

  /**
   * JSONL mirror options.
   *
   * @param enabled whether the JSONL mirror is active
   * @param path destination file path for the mirror
   */
  public record JsonlMirror(boolean enabled, String path) {}

  /**
   * Job blocks.
   *
   * @param backup configuration for the scheduled backup job
   * @param cleanup configuration for maintenance sweeps
   */
  public record Jobs(Backup backup, Cleanup cleanup) {}

  /**
   * Backup job configuration.
   *
   * @param enabled whether the scheduled backup should run
   * @param schedule cron expression describing the run schedule
   * @param outDir destination directory for export artifacts
   * @param onMissed how to react when a scheduled execution was missed
   * @param gzip whether to gzip the export output
   * @param prune retention rules for older backups
   */
  public record Backup(
      boolean enabled,
      String schedule,
      String outDir,
      OnMissed onMissed,
      boolean gzip,
      Prune prune) {}

  /**
   * Prune policy for backups.
   *
   * @param keepDays maximum age in days to retain
   * @param keepMax maximum number of backup files to keep
   */
  public record Prune(int keepDays, int keepMax) {}

  /**
   * Cleanup job configuration wrapper.
   *
   * @param idempotencySweep job that trims expired idempotency entries
   */
  public record Cleanup(IdempotencySweep idempotencySweep) {}

  /**
   * Idempotency sweep config.
   *
   * @param enabled whether the job should be scheduled
   * @param schedule cron expression for execution times
   * @param retentionDays how many days to keep completed entries
   * @param batchLimit maximum rows deleted per batch iteration
   */
  public record IdempotencySweep(
      boolean enabled, String schedule, int retentionDays, int batchLimit) {}

  /**
   * Logging block.
   *
   * @param json whether to emit structured JSON logs
   * @param slowQueryMs threshold for slow-query warnings
   * @param level textual log level for the console logger
   */
  public record Log(boolean json, long slowQueryMs, String level) {}

  /** On-missed schedule policy for backups. */
  public enum OnMissed {
    /** Do nothing; missed executions are ignored. */
    SKIP,
    /** Execute the missed job immediately when the server next starts. */
    RUN_AT_NEXT_STARTUP;

    static OnMissed from(String raw) {
      if (raw == null) return SKIP;
      return switch (raw.toLowerCase(Locale.ROOT)) {
        case "runatnextstartup" -> RUN_AT_NEXT_STARTUP;
        default -> SKIP;
      };
    }
  }
}
