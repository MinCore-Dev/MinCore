/* MinCore © 2025 — MIT */
package dev.mincore.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime configuration loaded from {@code config/mincore.json5}.
 *
 * <p>On first run, writes a default JSON5 file with comments and then parses it as JSON (comments
 * stripped).
 *
 * <h2>JDBC &amp; Pool</h2>
 *
 * Values are mapped into a full JDBC URL for MariaDB and Hikari pool settings.
 *
 * <h2>Ledger</h2>
 *
 * Optional core ledger, configurable retention and JSONL mirroring.
 *
 * @param jdbcUrl JDBC URL assembled from file contents
 * @param user database username
 * @param password database password
 * @param poolMax Hikari max pool size
 * @param poolMinIdle Hikari minimum idle connections
 * @param poolConnTimeoutMs Hikari connection timeout
 * @param poolIdleTimeoutMs Hikari idle timeout
 * @param poolMaxLifetimeMs Hikari max lifetime
 * @param forceUtc whether to enforce UTC per-connection
 * @param ledgerEnabled whether core ledger is enabled
 * @param ledgerRetentionDays delete rows older than N days (>0 to enable)
 * @param ledgerFileEnabled mirror ledger events to JSONL file
 * @param ledgerFilePath JSONL file path (created if missing)
 */
public record Config(
    String jdbcUrl,
    String user,
    String password,
    int poolMax,
    int poolMinIdle,
    long poolConnTimeoutMs,
    long poolIdleTimeoutMs,
    long poolMaxLifetimeMs,
    boolean forceUtc,
    boolean ledgerEnabled,
    int ledgerRetentionDays,
    boolean ledgerFileEnabled,
    String ledgerFilePath) {

  /**
   * Loads configuration, writing a default file if it does not exist.
   *
   * @param path config file path
   * @return parsed config
   */
  public static Config loadOrWriteDefault(Path path) {
    try {
      if (!Files.exists(path)) {
        Files.createDirectories(path.getParent());
        String def =
            """
            // MinCore MariaDB config (JSON5). See spec for full schema.
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
                    maxLifetimeMs: 1700000
                  }
                },
                runtime: { reconnectEveryS: 10 },

                // Core ledger: set enabled=true to record economy/add-on events.
                ledger: {
                  enabled: true,
                  retentionDays: 60,                 // delete rows older than N days (0 = keep forever)
                  file: {
                    enabled: false,                  // also mirror to a JSONL file
                    path: "./logs/mincore-ledger.jsonl"
                  }
                },

                jobs: {
                  backup: { enabled: false, schedule: "0 0 5 * * *", outDir: "./backups/mincore", onMissed: "skip", gzip: true },
                  cleanup: { idempotencySweep: { enabled: true, schedule: "0 30 4 * * *", retentionDays: 30, batchLimit: 5000 } }
                },
                log: { json: false }
              }
            }
            """;
        Files.writeString(path, def, StandardCharsets.UTF_8);
      }

      // Strip comments and trailing commas from JSON5
      String raw = Files.readString(path);
      String json =
          raw.replaceAll("(?s)/\\*.*?\\*/", "")
              .replaceAll("(?m)//.*$", "")
              .replaceAll(",(?=\\s*[}\\]])", "");

      Gson gson = new Gson();
      JsonObject root = gson.fromJson(json, JsonObject.class);
      JsonObject core = root.getAsJsonObject("core");
      JsonObject db = core.getAsJsonObject("db");

      String host = db.get("host").getAsString();
      int port = db.get("port").getAsInt();
      String database = db.get("database").getAsString();
      String user = db.get("user").getAsString();
      String password = db.get("password").getAsString();

      JsonObject pool = db.getAsJsonObject("pool");
      int maxPool = pool.get("maxPoolSize").getAsInt();
      int minIdle = pool.get("minimumIdle").getAsInt();
      long connTimeout = pool.get("connectionTimeoutMs").getAsLong();
      long idleTimeout = pool.get("idleTimeoutMs").getAsLong();
      long maxLifetime = pool.get("maxLifetimeMs").getAsLong();

      boolean forceUtc = true;
      if (db.has("session") && db.getAsJsonObject("session").has("forceUtc")) {
        forceUtc = db.getAsJsonObject("session").get("forceUtc").getAsBoolean();
      }

      // Ledger block (optional)
      boolean ledgerEnabled = false;
      int ledgerRetentionDays = 0;
      boolean ledgerFileEnabled = false;
      String ledgerFilePath = "./logs/mincore-ledger.jsonl";
      if (core.has("ledger")) {
        JsonObject ledger = core.getAsJsonObject("ledger");
        ledgerEnabled = ledger.has("enabled") && ledger.get("enabled").getAsBoolean();
        if (ledger.has("retentionDays"))
          ledgerRetentionDays = ledger.get("retentionDays").getAsInt();
        if (ledger.has("file")) {
          JsonObject f = ledger.getAsJsonObject("file");
          if (f.has("enabled")) ledgerFileEnabled = f.get("enabled").getAsBoolean();
          if (f.has("path")) ledgerFilePath = f.get("path").getAsString();
        }
      }

      String jdbcUrl =
          String.format(
              "jdbc:mariadb://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC",
              host, port, database);

      return new Config(
          jdbcUrl,
          user,
          password,
          maxPool,
          minIdle,
          connTimeout,
          idleTimeout,
          maxLifetime,
          forceUtc,
          ledgerEnabled,
          ledgerRetentionDays,
          ledgerFileEnabled,
          ledgerFilePath);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load config: " + path, e);
    }
  }
}
