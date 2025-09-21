/* MinCore © 2025 — MIT */
package dev.mincore.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.mincore.api.Ledger;
import dev.mincore.api.events.CoreEvents.BalanceChangedEvent;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MariaDB-backed ledger that writes immutable rows to {@code core_ledger} and may mirror entries to
 * a JSONL file for out-of-band inspection.
 *
 * <p><strong>Responsibilities</strong>
 *
 * <ul>
 *   <li>Listen to {@link BalanceChangedEvent} and persist a compact audit line (addon {@code core},
 *       op {@code balance}).
 *   <li>Expose a {@link Ledger} implementation for add-ons to record operations with optional
 *       idempotency.
 *   <li>Perform periodic TTL cleanup when {@link Config#ledgerRetentionDays()} is positive.
 * </ul>
 *
 * <p><strong>Thread-safety</strong> — Stateless aside from shared {@link DataSource}; uses a new
 * connection per call and is safe for concurrent use.
 *
 * <p><strong>Error handling</strong> — Write and read helpers swallow SQL exceptions and log
 * warnings. Admin commands present best-effort results to users.
 */
public final class LedgerImpl implements Ledger, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final Gson GSON = new Gson();

  private final DataSource ds;
  private final boolean enabled;
  private final boolean fileEnabled;
  private final Path filePath;
  private final int retentionDays;
  private AutoCloseable coreListener; // event unsubscription

  private LedgerImpl(
      DataSource ds, boolean enabled, boolean fileEnabled, Path filePath, int retentionDays) {
    this.ds = ds;
    this.enabled = enabled;
    this.fileEnabled = fileEnabled;
    this.filePath = filePath;
    this.retentionDays = retentionDays;
  }

  /**
   * Install the ledger module according to runtime configuration.
   *
   * <p>Steps:
   *
   * <ol>
   *   <li>Ensure the {@code core_ledger} table exists.
   *   <li>Subscribe to {@link BalanceChangedEvent} to record core balance diffs.
   *   <li>Schedule hourly TTL cleanup if retention is enabled.
   * </ol>
   *
   * @param services running service container
   * @param cfg runtime configuration
   * @return created instance (if disabled, returns a no-op instance to keep API stable)
   */
  public static LedgerImpl install(Services services, Config cfg) {
    var inst =
        new LedgerImpl(
            services instanceof CoreServices cs
                ? cs.pool()
                : null, // fallback path; CoreServices exposes DataSource internally
            cfg.ledgerEnabled(),
            cfg.ledgerFileEnabled(),
            Path.of(cfg.ledgerFilePath()),
            cfg.ledgerRetentionDays());

    if (!cfg.ledgerEnabled()) {
      LOG.info("(mincore) ledger: disabled by config");
      return inst;
    }

    // Ensure table exists
    try (var c = inst.ds.getConnection();
        var st = c.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS core_ledger (
            id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            ts_s            BIGINT UNSIGNED NOT NULL,
            addon_id        VARCHAR(64)     NOT NULL,
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
            KEY idx_addon        (addon_id),
            KEY idx_op           (op),
            KEY idx_from         (from_uuid),
            KEY idx_to           (to_uuid),
            KEY idx_reason       (reason),
            KEY idx_seq          (seq),
            KEY idx_idem_scope   (idem_scope)
          ) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
          """);
    } catch (SQLException e) {
      LOG.error("(mincore) ledger: failed to ensure table", e);
    }

    // Subscribe to core balance changes
    inst.coreListener =
        services
            .events()
            .onBalanceChanged(
                e -> {
                  long delta = e.newUnits() - e.oldUnits();
                  inst.writeRow(
                      Instant.now().getEpochSecond(),
                      "core",
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

    // Schedule TTL cleanup
    if (cfg.ledgerRetentionDays() > 0) {
      ScheduledExecutorService sch = services.scheduler();
      long days = cfg.ledgerRetentionDays();
      sch.scheduleAtFixedRate(
          () -> {
            try (var c = inst.ds.getConnection();
                var ps = c.prepareStatement("DELETE FROM core_ledger WHERE ts_s < ? LIMIT 5000")) {
              long cutoff = Instant.now().getEpochSecond() - (days * 86400L);
              ps.setLong(1, cutoff);
              int n = ps.executeUpdate();
              if (n > 0) LOG.info("(mincore) ledger: cleanup removed {} rows", n);
            } catch (Throwable t) {
              LOG.warn("(mincore) ledger: cleanup failed", t);
            }
          },
          5, // initial delay
          TimeUnit.HOURS.toSeconds(1), // hourly sweep; rows older than retention are removed
          TimeUnit.SECONDS);
    }

    LOG.info(
        "(mincore) ledger: enabled (retention={} days, file={})",
        cfg.ledgerRetentionDays(),
        cfg.ledgerFileEnabled());
    return inst;
  }

  /**
   * Unsubscribe from core events and release any background listeners.
   *
   * @throws Exception if closing the listener throws
   */
  @Override
  public void close() throws Exception {
    if (coreListener != null) {
      try {
        coreListener.close();
      } catch (Throwable t) {
        LOG.debug("(mincore) ledger: close listener", t);
      }
    }
  }

  // ===== Public API used by add-ons via MinCoreApi.ledger() =====

  /**
   * Append a new ledger entry.
   *
   * <p>This is append-only; rows are never updated. If {@code idemKey} is provided, its SHA-256 is
   * stored in {@code idem_key_hash} to support duplicate suppression by higher layers.
   *
   * @param addonId add-on identifier, e.g. {@code "shop"}; required, max 64 characters
   * @param op short operation name, e.g. {@code "buy"} or {@code "refund"}; required, max 32
   *     characters
   * @param from optional payer UUID (may be {@code null})
   * @param to optional payee UUID (may be {@code null})
   * @param amount amount in smallest currency units
   * @param reason short human-friendly reason; required, max 64 characters
   * @param ok whether the operation succeeded
   * @param code optional short correlation/reference code (may be {@code null}, max 32 characters)
   * @param idemScope optional application scope for idempotency labeling (may be {@code null}, max
   *     64 characters)
   * @param idemKey optional opaque idempotency key; hashed before persistence
   * @param extraJson optional JSON string recorded verbatim in {@code extra_json} (may be {@code
   *     null})
   */
  @Override
  public void log(
      String addonId,
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
    if (!enabled) return;
    long now = Instant.now().getEpochSecond();
    byte[] idemHash = (idemKey == null || idemKey.isBlank()) ? null : sha256(idemKey);
    writeRow(
        now,
        safe(addonId, 64),
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

  // ===== Internal write helpers =====

  /**
   * Internal helper that writes a single row and optionally mirrors it to the JSONL file.
   *
   * @param tsS event timestamp in epoch seconds (UTC)
   * @param addonId producer id
   * @param op short operation
   * @param from optional payer
   * @param to optional payee
   * @param amount amount in smallest units
   * @param reason short reason
   * @param ok success flag
   * @param code optional correlation code
   * @param seq optional sequence number (reserved for future)
   * @param idemScope optional idempotency scope label
   * @param idemKeyHash optional 32-byte SHA-256 of the idempotency key
   * @param oldUnits optional previous wallet units
   * @param newUnits optional new wallet units
   * @param serverNode optional node label
   * @param extraJson optional JSON payload
   */
  private void writeRow(
      long tsS,
      String addonId,
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
    // DB
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                """
                INSERT INTO core_ledger
                  (ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code,
                   seq, idem_scope, idem_key_hash, old_units, new_units, server_node, extra_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
      int i = 1;
      ps.setLong(i++, tsS);
      ps.setString(i++, addonId);
      ps.setString(i++, op);
      if (from == null) ps.setNull(i++, java.sql.Types.BINARY);
      else ps.setBytes(i++, uuidToBytes(from));
      if (to == null) ps.setNull(i++, java.sql.Types.BINARY);
      else ps.setBytes(i++, uuidToBytes(to));
      ps.setLong(i++, amount);
      ps.setString(i++, reason);
      ps.setBoolean(i++, ok);
      if (code == null) ps.setNull(i++, java.sql.Types.VARCHAR);
      else ps.setString(i++, code);
      ps.setLong(i++, seq);
      if (idemScope == null) ps.setNull(i++, java.sql.Types.VARCHAR);
      else ps.setString(i++, idemScope);
      if (idemKeyHash == null) ps.setNull(i++, java.sql.Types.BINARY);
      else ps.setBytes(i++, idemKeyHash);
      if (oldUnits == null) ps.setNull(i++, java.sql.Types.BIGINT);
      else ps.setLong(i++, oldUnits);
      if (newUnits == null) ps.setNull(i++, java.sql.Types.BIGINT);
      else ps.setLong(i++, newUnits);
      ps.setString(i++, serverNode);
      if (extraJson == null) ps.setNull(i++, java.sql.Types.LONGVARCHAR);
      else ps.setString(i++, extraJson);
      ps.executeUpdate();
    } catch (Throwable t) {
      LOG.warn("(mincore) ledger: write failed", t);
    }

    // Optional JSONL
    if (fileEnabled) {
      try {
        ensureParent(filePath);
        JsonObject j = new JsonObject();
        j.addProperty("ts", tsS);
        j.addProperty("addon", addonId);
        j.addProperty("op", op);
        if (from != null) j.addProperty("from", from.toString());
        if (to != null) j.addProperty("to", to.toString());
        j.addProperty("amount", amount);
        j.addProperty("reason", reason);
        j.addProperty("ok", ok);
        if (code != null) j.addProperty("code", code);
        if (seq > 0) j.addProperty("seq", seq);
        if (idemScope != null) j.addProperty("idemScope", idemScope);
        if (oldUnits != null) j.addProperty("oldUnits", oldUnits);
        if (newUnits != null) j.addProperty("newUnits", newUnits);
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
        LOG.warn("(mincore) ledger: file write failed ({})", filePath, ioe);
      }
    }
  }

  // ===== Query helpers for AdminCommands =====

  /**
   * Fetch most recent rows, newest first.
   *
   * @param limit maximum number of rows to return (clamped to 1..200)
   * @return immutable list of rows; best-effort if a query error occurs
   */
  public java.util.List<Row> recent(int limit) {
    return query(
        """
        SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger ORDER BY id DESC LIMIT ?
        """,
        ps -> ps.setInt(1, Math.max(1, Math.min(200, limit))));
  }

  /**
   * Fetch rows involving a specific player (as payer or payee), newest first.
   *
   * @param player player UUID to match against {@code from_uuid} or {@code to_uuid}
   * @param limit maximum number of rows to return (clamped to 1..200)
   * @return immutable list of rows; best-effort if a query error occurs
   */
  public java.util.List<Row> byPlayer(UUID player, int limit) {
    return query(
        """
        SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger
        WHERE (from_uuid = ? OR to_uuid = ?)
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          byte[] b = uuidToBytes(player);
          ps.setBytes(1, b);
          ps.setBytes(2, b);
          ps.setInt(3, Math.max(1, Math.min(200, limit)));
        });
  }

  /**
   * Fetch rows whose {@code reason} contains a case-insensitive substring, newest first.
   *
   * @param needle substring to search for (used with {@code LIKE '%needle%'})
   * @param limit maximum number of rows to return (clamped to 1..200)
   * @return immutable list of rows; best-effort if a query error occurs
   */
  public java.util.List<Row> byReasonContains(String needle, int limit) {
    return query(
        """
        SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger WHERE reason LIKE ?
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          ps.setString(1, "%" + needle + "%");
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  /**
   * Fetch rows emitted by a given add-on, newest first.
   *
   * @param addonId add-on identifier, e.g. {@code "shop"}
   * @param limit maximum number of rows to return (clamped to 1..200)
   * @return immutable list of rows; best-effort if a query error occurs
   */
  public java.util.List<Row> byAddon(String addonId, int limit) {
    return query(
        """
        SELECT id, ts_s, addon_id, op, from_uuid, to_uuid, amount, reason, ok, code
        FROM core_ledger WHERE addon_id = ?
        ORDER BY id DESC LIMIT ?
        """,
        ps -> {
          ps.setString(1, addonId);
          ps.setInt(2, Math.max(1, Math.min(200, limit)));
        });
  }

  /**
   * Immutable projection used by admin commands for compact chat rendering.
   *
   * <p>All fields map directly to columns selected by the query helpers.
   */
  public static final class Row {
    /** Surrogate row id (monotonic, ascending). */
    public final long id;

    /** Event timestamp in epoch seconds (UTC). */
    public final long tsS;

    /** Amount in smallest currency units. */
    public final long amount;

    /** Add-on identifier (e.g., {@code "core"} or {@code "shop"}). */
    public final String addon;

    /** Operation name (e.g., {@code "balance"}, {@code "buy"}). */
    public final String op;

    /** Short, human-readable reason. */
    public final String reason;

    /** Optional correlation/reference code; may be {@code null}. */
    public final String code;

    /** Optional payer UUID; may be {@code null}. */
    public final UUID from;

    /** Optional payee UUID; may be {@code null}. */
    public final UUID to;

    /** Whether the operation succeeded. */
    public final boolean ok;

    /**
     * Construct a row projection.
     *
     * @param id row id
     * @param tsS event time (epoch seconds)
     * @param addon add-on id
     * @param op operation
     * @param from optional payer
     * @param to optional payee
     * @param amount amount in smallest units
     * @param reason short reason
     * @param ok success flag
     * @param code optional reference code
     */
    Row(
        long id,
        long tsS,
        String addon,
        String op,
        UUID from,
        UUID to,
        long amount,
        String reason,
        boolean ok,
        String code) {
      this.id = id;
      this.tsS = tsS;
      this.addon = addon;
      this.op = op;
      this.from = from;
      this.to = to;
      this.amount = amount;
      this.reason = reason;
      this.ok = ok;
      this.code = code;
    }
  }

  /** Small binder interface for prepared-statement population. */
  private interface PSF {
    void bind(PreparedStatement ps) throws SQLException;
  }

  /**
   * Execute a parameterized query and materialize rows into {@link Row} projections.
   *
   * @param sql SQL text with placeholders
   * @param binder functional binder that sets parameters on the prepared statement
   * @return immutable list of {@link Row}; empty on error
   */
  private java.util.List<Row> query(String sql, PSF binder) {
    var out = new java.util.ArrayList<Row>(32);
    try (var c = ds.getConnection();
        var ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          long id = rs.getLong(1);
          long ts = rs.getLong(2);
          String addon = rs.getString(3);
          String op = rs.getString(4);
          byte[] f = rs.getBytes(5);
          byte[] t = rs.getBytes(6);
          long amt = rs.getLong(7);
          String reason = rs.getString(8);
          boolean ok = rs.getBoolean(9);
          String code = rs.getString(10);
          out.add(
              new Row(id, ts, addon, op, bytesToUuid(f), bytesToUuid(t), amt, reason, ok, code));
        }
      }
    } catch (Throwable e) {
      LOG.warn("(mincore) ledger: query failed", e);
    }
    return out;
  }

  // ===== Utilities =====

  private static void ensureParent(Path p) throws IOException {
    Path parent = Objects.requireNonNull(p.getParent(), "parent");
    if (!Files.isDirectory(parent)) Files.createDirectories(parent);
  }

  private static String safe(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    if (t.length() > max) t = t.substring(0, max);
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

  private static byte[] uuidToBytes(UUID u) {
    if (u == null) return null;
    long msb = u.getMostSignificantBits();
    long lsb = u.getLeastSignificantBits();
    byte[] b = new byte[16];
    for (int i = 0; i < 8; i++) b[i] = (byte) (msb >>> (8 * (7 - i)));
    for (int i = 0; i < 8; i++) b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
    return b;
  }

  private static UUID bytesToUuid(byte[] b) {
    if (b == null || b.length != 16) return null;
    long msb = 0, lsb = 0;
    for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xff);
    for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xff);
    return new UUID(msb, lsb);
  }

  /** Expose the pooled data source to internal callers that need it. */
  DataSource dataSource() {
    return ds;
  }
}
