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
 *   <li>Parses i18n, timezone, scheduler, and logging blocks.
 * </ul>
 */
public final class Config {

  private static final String TEMPLATE =
      """
      // MinCore v0.2.0 configuration (JSON5 with comments)
      // Drop into config/mincore.json5. Environment overrides: MINCORE_DB_HOST|PORT|DATABASE|USER|PASSWORD.
      {
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
              allowPlayerOverride: false,
              autoDetect: false
            }
          },
          i18n: {
            defaultLocale: "en_US",
            enabledLocales: [ "en_US" ],
            fallbackLocale: "en_US"
          },
          ledger: {
            enabled: true,
            retentionDays: 0,
            file: {
              enabled: false,
              path: "./logs/mincore-ledger.jsonl"
            }
          },
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
  private final Ledger ledger;
  private final Jobs jobs;
  private final Log log;

  private Config(Db db, Runtime runtime, Time time, I18n i18n, Ledger ledger, Jobs jobs, Log log) {
    this.db = db;
    this.runtime = runtime;
    this.time = time;
    this.i18n = i18n;
    this.ledger = ledger;
    this.jobs = jobs;
    this.log = log;
  }

  /** Database connection block. */
  public Db db() {
    return db;
  }

  /** Runtime behavior (reconnect cadence, etc.). */
  public Runtime runtime() {
    return runtime;
  }

  /** Timezone display configuration. */
  public Time time() {
    return time;
  }

  /** Localization configuration. */
  public I18n i18n() {
    return i18n;
  }

  /** Ledger configuration. */
  public Ledger ledger() {
    return ledger;
  }

  /** Scheduled job configuration. */
  public Jobs jobs() {
    return jobs;
  }

  /** Logging configuration. */
  public Log log() {
    return log;
  }

  /** Convenience accessor: whether the ledger is enabled. */
  public boolean ledgerEnabled() {
    return ledger.enabled();
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
      if (!Files.exists(path)) {
        Files.createDirectories(path.getParent());
        Files.writeString(path, TEMPLATE, StandardCharsets.UTF_8);
      }

      ConfigTemplateWriter.writeExample(
          path.getParent().resolve("mincore.json5.example"), TEMPLATE);

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
      I18n i18n = parseI18n(optObject(core, "i18n"));
      Ledger ledger = parseLedger(optObject(core, "ledger"));
      Jobs jobs = parseJobs(optObject(core, "jobs"));
      Log log = parseLog(optObject(core, "log"));

      return new Config(db, runtime, time, i18n, ledger, jobs, log);
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
      zoneId = ZoneId.of("UTC");
    }
    return new Time(new Display(zoneId, allowOverride, autoDetect));
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
              false, "0 45 4 * * *", "./backups/mincore", OnMissed.SKIP, true, new Prune(14, 60)),
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

  /** Database settings. */
  public record Db(
      String host,
      int port,
      String database,
      String user,
      String password,
      boolean tlsEnabled,
      boolean forceUtc,
      Pool pool) {

    /** Fully formed JDBC URL (MariaDB tuned for UTF-8 + UTC). */
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
        url.append("&useSSL=true&requireSSL=true");
      }
      return url.toString();
    }
  }

  /** Connection pool tuning. */
  public record Pool(
      int maxPoolSize,
      int minimumIdle,
      long connectionTimeoutMs,
      long idleTimeoutMs,
      long maxLifetimeMs,
      int startupAttempts) {}

  /** Runtime reconnect cadence. */
  public record Runtime(int reconnectEveryS) {}

  /** Display timezone handling. */
  public record Time(Display display) {}

  /** Player-visible timezone preferences. */
  public record Display(ZoneId defaultZone, boolean allowPlayerOverride, boolean autoDetect) {}

  /** Localization configuration. */
  public record I18n(Locale defaultLocale, List<String> enabledLocales, Locale fallbackLocale) {}

  /** Ledger options. */
  public record Ledger(boolean enabled, int retentionDays, JsonlMirror jsonlMirror) {}

  /** JSONL mirror options. */
  public record JsonlMirror(boolean enabled, String path) {}

  /** Job blocks. */
  public record Jobs(Backup backup, Cleanup cleanup) {}

  /** Backup job configuration. */
  public record Backup(
      boolean enabled,
      String schedule,
      String outDir,
      OnMissed onMissed,
      boolean gzip,
      Prune prune) {}

  /** Prune policy for backups. */
  public record Prune(int keepDays, int keepMax) {}

  /** Cleanup job configuration wrapper. */
  public record Cleanup(IdempotencySweep idempotencySweep) {}

  /** Idempotency sweep config. */
  public record IdempotencySweep(
      boolean enabled, String schedule, int retentionDays, int batchLimit) {}

  /** Logging block. */
  public record Log(boolean json, long slowQueryMs, String level) {}

  /** On-missed schedule policy for backups. */
  public enum OnMissed {
    SKIP,
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
