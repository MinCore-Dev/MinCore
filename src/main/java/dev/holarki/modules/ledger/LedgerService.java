/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.ledger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.holarki.api.ErrorCode;
import dev.holarki.api.Ledger;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.core.Config;
import dev.holarki.core.Metrics;
import dev.holarki.core.SqlErrorCodes;
import dev.holarki.util.Uuids;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MariaDB-backed ledger service scoped to the ledger module.
 *
 * <p>Persists immutable rows to {@code core_ledger}, mirrors entries to JSONL when enabled, and
 * exposes the {@link Ledger} API to bundled modules and operator automation.
 */
public final class LedgerService implements Ledger, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final Gson GSON = new Gson();

  private final ModuleDatabase database;
  private final boolean enabled;
  private final boolean fileEnabled;
  private final Path filePath;
  private final int retentionDays;
  private final Metrics metrics;
  private AutoCloseable coreListener; // event unsubscription
  private volatile ScheduledFuture<?> retentionFuture;

  private LedgerService(
      ModuleDatabase database,
      boolean enabled,
      boolean fileEnabled,
      Path filePath,
      int retentionDays,
      Metrics metrics) {
    this.database = Objects.requireNonNull(database, "database");
    this.enabled = enabled;
    this.fileEnabled = enabled && fileEnabled && filePath != null;
    this.filePath = this.fileEnabled ? filePath : null;
    this.retentionDays = retentionDays;
    this.metrics = metrics;
  }

  /**
   * Installs the ledger service using the provided dependencies and configuration.
   *
   * @param database shared module database helper
   * @param events core event bus
   * @param scheduler background scheduler for TTL cleanup
   * @param metrics metrics registry (may be {@code null})
   * @param ledgerCfg runtime configuration for the ledger module
   * @return initialized ledger service
   */
  public static LedgerService install(
      ModuleDatabase database,
      CoreEvents events,
      ScheduledExecutorService scheduler,
      Metrics metrics,
      Config.Ledger ledgerCfg) {
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(ledgerCfg, "ledgerCfg");

    LedgerService service =
        new LedgerService(
            database,
            ledgerCfg.enabled(),
            ledgerCfg.jsonlMirror().enabled(),
            safePath(ledgerCfg.jsonlMirror().path()),
            ledgerCfg.retentionDays(),
            metrics);

    if (!ledgerCfg.enabled()) {
      LOG.info("(holarki) ledger: disabled by config");
      return service;
    }

    try {
      database
          .schema()
          .ensureTable(
              "core_ledger",
              """
          CREATE TABLE IF NOT EXISTS core_ledger (
            id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            ts_s            BIGINT UNSIGNED NOT NULL,
            module_id       VARCHAR(64)     NOT NULL,
            op              VARCHAR(32)     NOT NULL,
            from_uuid       BINARY(16)      NULL,
            to_uuid         BINARY(16)      NULL,
            amount          BIGINT          NOT NULL,
            reason          VARCHAR(64)     NOT NULL,
            ok              TINYINT(1)      NOT NULL,
            code            VARCHAR(32)     NULL,
            seq             BIGINT UNSIGNED NOT NULL DEFAULT 0,
            idem_scope      VARCHAR(64)     NULL,
            idem_key_hash   BINARY(32)      NULL,
            old_units       BIGINT UNSIGNED NULL,
            new_units       BIGINT UNSIGNED NULL,
            server_node     VARCHAR(64)     NULL,
            extra_json      MEDIUMTEXT      NULL,
            PRIMARY KEY (id),
            KEY idx_ts           (ts_s),
            KEY idx_module       (module_id),
            KEY idx_op           (op),
            KEY idx_from         (from_uuid),
            KEY idx_to           (to_uuid),
            KEY idx_reason       (reason),
            KEY idx_seq          (seq),
            KEY idx_idem_scope   (idem_scope)
          ) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
          """);
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      LOG.error(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "ledger.ensureTable",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
      throw new IllegalStateException("failed to initialize ledger schema", e);
    }

    service.coreListener =
        events.onBalanceChanged(
            e -> {
              long delta = e.newUnits() - e.oldUnits();
              service.writeRow(
                  Instant.now().getEpochSecond(),
                  "core.ledger",
                  "balance",
                  e.player(),
                  null,
                  delta,
                  e.reason(),
                  true,
                  null,
                  e.seq(),
                  null,
                  null,
                  e.oldUnits(),
                  e.newUnits(),
                  "srv",
                  null);
            });

    if (ledgerCfg.retentionDays() > 0) {
      long days = ledgerCfg.retentionDays();
      try {
        service.retentionFuture =
            scheduler.scheduleAtFixedRate(
                () -> service.cleanup(days),
                5,
                TimeUnit.HOURS.toSeconds(1),
                TimeUnit.SECONDS);
      } catch (RejectedExecutionException e) {
        LOG.warn("(holarki) ledger: retention scheduling rejected; disabling cleanup", e);
        service.retentionFuture = null;
      } catch (RuntimeException e) {
        LOG.warn("(holarki) ledger: retention scheduling failed; disabling cleanup", e);
        service.retentionFuture = null;
      }
    }

    LOG.info(
        "(holarki) ledger: enabled (retention={} days, file={})",
        ledgerCfg.retentionDays(),
        service.fileEnabled);
    return service;
  }

  private static Path safePath(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Path.of(value);
    } catch (Exception e) {
      LOG.warn("(holarki) ledger: invalid mirror path {}; disabling file mirror", value, e);
      return null;
    }
  }

  @Override
  public void close() throws Exception {
    ScheduledFuture<?> future = this.retentionFuture;
    this.retentionFuture = null;
    if (future != null) {
      future.cancel(false);
    }
    if (coreListener != null) {
      try {
        coreListener.close();
      } catch (Throwable t) {
        LOG.debug("(holarki) ledger: close listener", t);
      }
    }
  }

  @Override
  public void log(
      String moduleId,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code,
      String idemScope,
      String idemKey,
      String extraJson) {
    if (!enabled) {
      return;
    }
    long now = Instant.now().getEpochSecond();
    byte[] idemHash = (idemKey == null || idemKey.isBlank()) ? null : sha256(idemKey);
    writeRow(
        now,
        safe(moduleId, 64),
        safe(op, 32),
        from,
        to,
        amount,
        safe(reason, 64),
        ok,
        code == null ? null : safe(code, 32),
        0L,
        idemScope == null ? null : safe(idemScope, 64),
        idemHash,
        null,
        null,
        "srv",
        extraJson);
  }

  private void writeRow(
      long tsS,
      String moduleId,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code,
      long seq,
      String idemScope,
      byte[] idemKeyHash,
      Long oldUnits,
      Long newUnits,
      String serverNode,
      String extraJson) {
    try {
      database.withRetry(
          () -> {
            try (Connection c = database.borrowConnection();
                PreparedStatement ps =
                    c.prepareStatement(
                        """
                        INSERT INTO core_ledger
                          (ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code,
                           seq, idem_scope, idem_key_hash, old_units, new_units, server_node, extra_json)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
              int i = 1;
              ps.setLong(i++, tsS);
              ps.setString(i++, moduleId);
              ps.setString(i++, op);
              if (from == null) {
                ps.setNull(i++, java.sql.Types.BINARY);
              } else {
                ps.setBytes(i++, Uuids.toBytes(from));
              }
              if (to == null) {
                ps.setNull(i++, java.sql.Types.BINARY);
              } else {
                ps.setBytes(i++, Uuids.toBytes(to));
              }
              ps.setLong(i++, amount);
              ps.setString(i++, reason);
              ps.setBoolean(i++, ok);
              if (code == null) {
                ps.setNull(i++, java.sql.Types.VARCHAR);
              } else {
                ps.setString(i++, code);
              }
              ps.setLong(i++, seq);
              if (idemScope == null) {
                ps.setNull(i++, java.sql.Types.VARCHAR);
              } else {
                ps.setString(i++, idemScope);
              }
              if (idemKeyHash == null) {
                ps.setNull(i++, java.sql.Types.BINARY);
              } else {
                ps.setBytes(i++, idemKeyHash);
              }
              if (oldUnits == null) {
                ps.setNull(i++, java.sql.Types.BIGINT);
              } else {
                ps.setLong(i++, oldUnits);
              }
              if (newUnits == null) {
                ps.setNull(i++, java.sql.Types.BIGINT);
              } else {
                ps.setLong(i++, newUnits);
              }
              ps.setString(i++, serverNode);
              if (extraJson == null) {
                ps.setNull(i++, java.sql.Types.LONGVARCHAR);
              } else {
                ps.setString(i++, extraJson);
              }
              ps.executeUpdate();
              if (metrics != null) {
                metrics.recordLedgerWrite(true, null);
              }
            }
            return null;
          });
    } catch (SQLException e) {
      ErrorCode errorCode = SqlErrorCodes.classify(e);
      if (metrics != null) {
        metrics.recordLedgerWrite(false, errorCode);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          errorCode,
          "ledger.write",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
      return;
    } catch (Throwable t) {
      LOG.warn(
          "(holarki) code={} op={} message={}",
          ErrorCode.CONNECTION_LOST,
          "ledger.write",
          t.getMessage(),
          t);
      if (metrics != null) {
        metrics.recordLedgerWrite(false, ErrorCode.CONNECTION_LOST);
      }
      return;
    }

    if (fileEnabled && filePath != null) {
      try {
        ensureParent(filePath);
        JsonObject j = new JsonObject();
        j.addProperty("ts", tsS);
        j.addProperty("module", moduleId);
        j.addProperty("op", op);
        if (from != null) {
          j.addProperty("from", from.toString());
        }
        if (to != null) {
          j.addProperty("to", to.toString());
        }
        j.addProperty("amount", amount);
        j.addProperty("reason", reason);
        j.addProperty("ok", ok);
        if (code != null) {
          j.addProperty("code", code);
        }
        if (seq > 0) {
          j.addProperty("seq", seq);
        }
        if (idemScope != null) {
          j.addProperty("idemScope", idemScope);
        }
        if (oldUnits != null) {
          j.addProperty("oldUnits", oldUnits);
        }
        if (newUnits != null) {
          j.addProperty("newUnits", newUnits);
        }
        if (extraJson != null && !extraJson.isBlank()) {
          try {
            j.add("extra", GSON.fromJson(extraJson, JsonObject.class));
          } catch (Throwable ignore) {
            j.addProperty("extraRaw", extraJson);
          }
        }
        try (BufferedWriter w =
            Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
          w.write(GSON.toJson(j));
          w.write("\n");
        }
      } catch (IOException ioe) {
        LOG.warn(
            "(holarki) code={} op={} message={} path={}",
            "FILE_IO",
            "ledger.fileWrite",
            ioe.getMessage(),
            filePath,
            ioe);
      }
    }
  }

  private void cleanup(long retentionDays) {
    try {
      database.withRetry(
          () -> {
            long cutoff = Instant.now().getEpochSecond() - (retentionDays * 86400L);
            try (Connection c = database.borrowConnection();
                PreparedStatement ps =
                    c.prepareStatement("DELETE FROM core_ledger WHERE ts_s < ? LIMIT 5000")) {
              ps.setLong(1, cutoff);
              int removed = ps.executeUpdate();
              if (removed > 0) {
                LOG.info("(holarki) ledger: cleanup removed {} rows", removed);
              }
            }
            return null;
          });
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "ledger.cleanup",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    } catch (Throwable t) {
      LOG.warn(
          "(holarki) code={} op={} message={}",
          ErrorCode.CONNECTION_LOST,
          "ledger.cleanup",
          t.getMessage(),
          t);
    }
  }

  public java.util.List<Row> recent(int limit) {
    return query(
        """
        SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger ORDER BY id DESC LIMIT ?
        """,
        ps -> ps.setInt(1, Math.max(1, Math.min(200, limit))));
  }

  public java.util.List<Row> byPlayer(UUID player, int limit) {
    return query(
        """
        SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger
        WHERE (from_uuid = ? OR to_uuid = ?)
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          byte[] b = Uuids.toBytes(player);
          ps.setBytes(1, b);
          ps.setBytes(2, b);
          ps.setInt(3, Math.max(1, Math.min(200, limit)));
        });
  }

  public java.util.List<Row> byModule(String moduleId, int limit) {
    return query(
        """
        SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger
        WHERE module_id = ?
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          ps.setString(1, safe(moduleId, 64));
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  public java.util.List<Row> byReason(String reason, int limit) {
    return query(
        """
        SELECT id, ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger
        WHERE reason LIKE ?
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          ps.setString(1, "%" + safe(reason, 64) + "%");
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  private java.util.List<Row> query(String sql, PSF binder) {
    var out = new java.util.ArrayList<Row>(32);
    try (Connection c = database.borrowConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          long id = rs.getLong(1);
          long ts = rs.getLong(2);
          String module = rs.getString(3);
          String op = rs.getString(4);
          byte[] f = rs.getBytes(5);
          byte[] t = rs.getBytes(6);
          long amt = rs.getLong(7);
          String reason = rs.getString(8);
          boolean ok = rs.getBoolean(9);
          String code = rs.getString(10);
          out.add(
              new Row(id, ts, module, op, bytesToUuid(f), bytesToUuid(t), amt, reason, ok, code));
        }
      }
    } catch (Throwable e) {
      LOG.warn("(holarki) ledger: query failed", e);
    }
    return out;
  }

  public record Row(
      long id,
      long tsS,
      String module,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code) {}

  private interface PSF {
    void bind(PreparedStatement ps) throws SQLException;
  }

  private static void ensureParent(Path p) throws IOException {
    Path parent = p.getParent();
    if (parent == null || Files.isDirectory(parent)) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException ioe) {
      LOG.warn(
          "(holarki) code={} op={} message={} path={}",
          "FILE_IO",
          "ledger.ensureParent",
          ioe.getMessage(),
          parent,
          ioe);
      throw ioe;
    }
  }

  private static String safe(String s, int max) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (t.length() > max) {
      t = t.substring(0, max);
    }
    return t;
  }

  private static byte[] sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return null;
    }
  }

  private static UUID bytesToUuid(byte[] b) {
    if (b == null || b.length != 16) {
      return null;
    }
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (b[i] & 0xff);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (b[i] & 0xff);
    }
    return new UUID(msb, lsb);
  }
}
