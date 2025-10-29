/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.Wallets;
import dev.holarki.api.Wallets.OperationResult;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.util.TokenBucketRateLimiter;
import dev.holarki.util.Uuids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MariaDB-backed implementation of {@link Wallets}. */
public final class WalletsImpl implements Wallets {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final int EVENT_VERSION = 1;
  private static final TokenBucketRateLimiter IDEM_LOG_LIMITER = new TokenBucketRateLimiter(4, 0.5);

  private final DataSource ds;
  private final EventBus events;
  private final DbHealth dbHealth;
  private final Metrics metrics;
  private final long idempotencyTtlSeconds;

  /**
   * Creates a wallet service backed by the given datasource and event bus.
   *
   * @param ds pooled datasource connected to the Holarki schema
   * @param events core event bus used to emit balance change notifications
   * @param dbHealth health monitor for degraded mode handling
   * @param metrics metrics registry for observability
   */
  public WalletsImpl(
      DataSource ds,
      EventBus events,
      DbHealth dbHealth,
      Metrics metrics,
      Duration idempotencyTtl) {
    this.ds = ds;
    this.events = events;
    this.dbHealth = dbHealth;
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(idempotencyTtl, "idempotencyTtl");
    long ttlSeconds = idempotencyTtl.getSeconds();
    this.idempotencyTtlSeconds = ttlSeconds <= 0 ? Long.MAX_VALUE : ttlSeconds;
  }

  @Override
  public long getBalance(UUID player) {
    if (player == null) return 0L;
    String sql = "SELECT balance_units FROM players WHERE uuid=?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(player));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          dbHealth.markSuccess();
          return rs.getLong(1);
        }
      }
      dbHealth.markSuccess();
    } catch (SQLException e) {
      dbHealth.markFailure(e);
      ErrorCode code = SqlErrorCodes.classify(e);
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "wallets.getBalance",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return 0L;
  }

  @Override
  public boolean deposit(UUID player, long amount, String reason) {
    return depositResult(player, amount, reason, autoKey()).ok();
  }

  @Override
  public boolean withdraw(UUID player, long amount, String reason) {
    return withdrawResult(player, amount, reason, autoKey()).ok();
  }

  @Override
  public boolean transfer(UUID from, UUID to, long amount, String reason) {
    return transferResult(from, to, amount, reason, autoKey()).ok();
  }

  @Override
  public OperationResult depositResult(UUID player, long amount, String reason, String idemKey) {
    if (!dbHealth.allowWrite("wallets.deposit")) {
      return OperationResult.failure(ErrorCode.DEGRADED_MODE, "database degraded");
    }
    if (player == null) {
      return OperationResult.failure(ErrorCode.UNKNOWN_PLAYER, "player required");
    }
    if (amount <= 0) {
      return OperationResult.failure(ErrorCode.INVALID_AMOUNT, "amount must be > 0");
    }
    String cleanReason = clampReason(reason);
    String payload = canonical("core:deposit", null, player, amount, canonicalReason(reason));
    return record(
        "deposit",
        applyIdempotent(
            "core:deposit", idemKey, payload, c -> applyDelta(c, player, amount, cleanReason)));
  }

  @Override
  public OperationResult withdrawResult(UUID player, long amount, String reason, String idemKey) {
    if (!dbHealth.allowWrite("wallets.withdraw")) {
      return OperationResult.failure(ErrorCode.DEGRADED_MODE, "database degraded");
    }
    if (player == null) {
      return OperationResult.failure(ErrorCode.UNKNOWN_PLAYER, "player required");
    }
    if (amount <= 0) {
      return OperationResult.failure(ErrorCode.INVALID_AMOUNT, "amount must be > 0");
    }
    String cleanReason = clampReason(reason);
    String payload = canonical("core:withdraw", player, null, amount, canonicalReason(reason));
    return record(
        "withdraw",
        applyIdempotent(
            "core:withdraw", idemKey, payload, c -> applyDelta(c, player, -amount, cleanReason)));
  }

  @Override
  public OperationResult transferResult(
      UUID from, UUID to, long amount, String reason, String idemKey) {
    if (!dbHealth.allowWrite("wallets.transfer")) {
      return OperationResult.failure(ErrorCode.DEGRADED_MODE, "database degraded");
    }
    if (from == null || to == null) {
      return OperationResult.failure(ErrorCode.UNKNOWN_PLAYER, "participants required");
    }
    if (amount <= 0) {
      return OperationResult.failure(ErrorCode.INVALID_AMOUNT, "amount must be > 0");
    }
    String cleanReason = clampReason(reason);
    String payload = canonical("core:transfer", from, to, amount, canonicalReason(reason));
    return record(
        "transfer",
        applyIdempotent(
            "core:transfer",
            idemKey,
            payload,
            c -> applyTransfer(c, from, to, amount, cleanReason)));
  }

  private Mutation applyDelta(Connection c, UUID player, long delta, String reason)
      throws SQLException {
    PlayerBalance before = lockBalance(c, player);
    if (before == null) {
      return Mutation.failure(OperationResult.failure(ErrorCode.UNKNOWN_PLAYER, "player missing"));
    }
    long newUnits = before.units + delta;
    if (newUnits < 0) {
      return Mutation.failure(
          OperationResult.failure(ErrorCode.INSUFFICIENT_FUNDS, "balance would go negative"));
    }

    long now = Instant.now().getEpochSecond();
    updateBalance(c, player, newUnits, now);
    long seq = nextSeq(c, player);
    CoreEvents.BalanceChangedEvent event =
        new CoreEvents.BalanceChangedEvent(
            player, seq, before.units, newUnits, reason, EVENT_VERSION);
    return Mutation.success(OperationResult.success(), List.of(event));
  }

  private Mutation applyTransfer(Connection c, UUID from, UUID to, long amount, String reason)
      throws SQLException {
    if (from.equals(to)) {
      return Mutation.success(OperationResult.success(), List.of());
    }
    UUID first = compare(from, to) <= 0 ? from : to;
    UUID second = first.equals(from) ? to : from;

    PlayerBalance firstBal = lockBalance(c, first);
    PlayerBalance secondBal = lockBalance(c, second);
    PlayerBalance fromBal = first.equals(from) ? firstBal : secondBal;
    PlayerBalance toBal = first.equals(from) ? secondBal : firstBal;

    if (fromBal == null || toBal == null) {
      return Mutation.failure(
          OperationResult.failure(ErrorCode.UNKNOWN_PLAYER, "participant missing"));
    }
    if (fromBal.units < amount) {
      return Mutation.failure(
          OperationResult.failure(ErrorCode.INSUFFICIENT_FUNDS, "insufficient funds"));
    }

    long now = Instant.now().getEpochSecond();
    long newFrom = fromBal.units - amount;
    long newTo = toBal.units + amount;

    updateBalance(c, from, newFrom, now);
    updateBalance(c, to, newTo, now);

    long fromSeq = nextSeq(c, from);
    long toSeq = nextSeq(c, to);

    CoreEvents.BalanceChangedEvent debit =
        new CoreEvents.BalanceChangedEvent(
            from, fromSeq, fromBal.units, newFrom, reason, EVENT_VERSION);
    CoreEvents.BalanceChangedEvent credit =
        new CoreEvents.BalanceChangedEvent(to, toSeq, toBal.units, newTo, reason, EVENT_VERSION);
    return Mutation.success(OperationResult.success(), List.of(debit, credit));
  }

  private PlayerBalance lockBalance(Connection c, UUID uuid) throws SQLException {
    String sql = "SELECT balance_units FROM players WHERE uuid=? FOR UPDATE";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(uuid));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          long bal = rs.getLong(1);
          return new PlayerBalance(uuid, bal);
        }
      }
    }
    return null;
  }

  private void updateBalance(Connection c, UUID uuid, long newUnits, long now) throws SQLException {
    String sql = "UPDATE players SET balance_units=?, updated_at_s=? WHERE uuid=?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, newUnits);
      ps.setLong(2, now);
      ps.setBytes(3, Uuids.toBytes(uuid));
      ps.executeUpdate();
    }
  }

  private long nextSeq(Connection c, UUID uuid) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO player_event_seq(uuid,seq) VALUES(?,1) "
                    + "ON DUPLICATE KEY UPDATE seq=LAST_INSERT_ID(seq+1)");
        Statement last = c.createStatement()) {
      ps.setBytes(1, Uuids.toBytes(uuid));
      ps.executeUpdate();
      try (ResultSet rs = last.executeQuery("SELECT LAST_INSERT_ID()")) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    return 0L;
  }

  private OperationResult applyIdempotent(
      String scope, String idemKey, String payload, OpWork work) {
    String insert =
        "INSERT INTO core_requests(key_hash,scope,payload_hash,ok,created_at_s,expires_at_s) "
            + "VALUES(?, ?, ?, 0, ?, ?) "
            + "ON DUPLICATE KEY UPDATE created_at_s=VALUES(created_at_s), "
            + "expires_at_s=VALUES(expires_at_s)";
    String select =
        "SELECT payload_hash, ok, expires_at_s FROM core_requests WHERE key_hash=? AND scope=? FOR UPDATE";
    String resetExpired =
        "UPDATE core_requests SET payload_hash=?, ok=0, created_at_s=?, expires_at_s=? "
            + "WHERE key_hash=? AND scope=?";
    String markOk = "UPDATE core_requests SET ok=1 WHERE key_hash=? AND scope=?";
    long now = Instant.now().getEpochSecond();
    long ttl = idempotencyTtlSeconds;
    long exp = ttl >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttl;
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      String effectiveKey = normalizeIdemKey(idemKey);
      byte[] keyHash = sha256(effectiveKey);
      byte[] payloadHash = sha256(payload);

      boolean hadRow = false;
      byte[] storedPayload = null;
      int storedOk = 0;
      long storedExpiresAt = Long.MIN_VALUE;

      try (PreparedStatement check = c.prepareStatement(select)) {
        check.setBytes(1, keyHash);
        check.setString(2, scope);
        try (ResultSet rs = check.executeQuery()) {
          if (rs.next()) {
            hadRow = true;
            storedPayload = rs.getBytes(1);
            storedOk = rs.getInt(2);
            storedExpiresAt = rs.getLong(3);
          }
        }
      }

      try (PreparedStatement ins = c.prepareStatement(insert)) {
        ins.setBytes(1, keyHash);
        ins.setString(2, scope);
        ins.setBytes(3, payloadHash);
        ins.setLong(4, now);
        ins.setLong(5, exp);
        ins.executeUpdate();
      }

      if (!hadRow) {
        storedPayload = payloadHash;
        storedOk = 0;
      } else if (storedExpiresAt <= now) {
        try (PreparedStatement reset = c.prepareStatement(resetExpired)) {
          reset.setBytes(1, payloadHash);
          reset.setLong(2, now);
          reset.setLong(3, exp);
          reset.setBytes(4, keyHash);
          reset.setString(5, scope);
          reset.executeUpdate();
        }
        storedPayload = payloadHash;
        storedOk = 0;
      }

      if (!java.util.Arrays.equals(storedPayload, payloadHash)) {
        c.rollback();
        logIdem(
            scope + ":mismatch",
            "(holarki) code={} op={} message={} key={}",
            ErrorCode.IDEMPOTENCY_MISMATCH,
            scope,
            "idempotency payload mismatch",
            effectiveKey);
        dbHealth.markSuccess();
        return OperationResult.failure(
            ErrorCode.IDEMPOTENCY_MISMATCH, "idempotency payload mismatch");
      }
      if (storedOk == 1) {
        c.commit();
        logIdem(
            scope + ":replay",
            "(holarki) code={} op={} message={} key={}",
            ErrorCode.IDEMPOTENCY_REPLAY,
            scope,
            "idempotent replay",
            effectiveKey);
        dbHealth.markSuccess();
        return OperationResult.success(ErrorCode.IDEMPOTENCY_REPLAY, null);
      }

      Mutation mutation;
      try {
        mutation = work.run(c);
      } catch (SQLException e) {
        c.rollback();
        ErrorCode code = SqlErrorCodes.classify(e);
        LOG.warn("(holarki) code={} op={} message={}", code, scope, e.getMessage(), e);
        if (code == ErrorCode.CONNECTION_LOST) {
          dbHealth.markFailure(e);
        } else {
          dbHealth.markSuccess();
        }
        return OperationResult.failure(code, "database error");
      }
      if (!mutation.result().ok()) {
        c.rollback();
        dbHealth.markSuccess();
        return mutation.result();
      }

      try (PreparedStatement done = c.prepareStatement(markOk)) {
        done.setBytes(1, keyHash);
        done.setString(2, scope);
        done.executeUpdate();
      }
      c.commit();
      dbHealth.markSuccess();
      dispatchEvents(mutation.events());
      return mutation.result();
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      LOG.warn("(holarki) code={} op={} message={}", code, scope, e.getMessage(), e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      return OperationResult.failure(code, "database error");
    }
  }

  private void dispatchEvents(List<CoreEvents.BalanceChangedEvent> eventsToPublish) {
    if (eventsToPublish == null || eventsToPublish.isEmpty()) {
      return;
    }
    for (CoreEvents.BalanceChangedEvent event : eventsToPublish) {
      events.fireBalanceChanged(event);
    }
  }

  private OperationResult record(String op, OperationResult result) {
    metrics.recordWalletOperation(op, result);
    return result;
  }

  private static void logIdem(String key, String fmt, Object... args) {
    if (IDEM_LOG_LIMITER.tryAcquire(key)) {
      LOG.info(fmt, args);
    }
  }

  private static String canonical(
      String scope, UUID from, UUID to, long amount, String reasonNormalized) {
    return scope
        + "|"
        + (from == null ? "00000000-0000-0000-0000-000000000000" : from.toString())
        + "|"
        + (to == null ? "00000000-0000-0000-0000-000000000000" : to.toString())
        + "|"
        + amount
        + "|"
        + reasonNormalized;
  }

  private static String canonicalReason(String reason) {
    return clampReason(reason).toLowerCase(Locale.ROOT);
  }

  private static String clampReason(String reason) {
    String r = reason == null ? "" : reason.trim();
    if (r.length() > 64) {
      r = r.substring(0, 64);
    }
    return r;
  }

  private static byte[] sha256(String input) {
    if (input == null) {
      input = "";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static int compare(UUID a, UUID b) {
    int cmp = Long.compare(a.getMostSignificantBits(), b.getMostSignificantBits());
    if (cmp != 0) return cmp;
    return Long.compare(a.getLeastSignificantBits(), b.getLeastSignificantBits());
  }

  private static String autoKey() {
    return "core:auto:" + UUID.randomUUID();
  }

  private static String normalizeIdemKey(String idemKey) {
    if (idemKey == null || idemKey.isBlank()) {
      return autoKey();
    }
    return idemKey;
  }

  private record Mutation(OperationResult result, List<CoreEvents.BalanceChangedEvent> events) {
    Mutation {
      result = Objects.requireNonNull(result, "result");
      events = events == null ? List.of() : List.copyOf(events);
    }

    static Mutation success(OperationResult result, List<CoreEvents.BalanceChangedEvent> events) {
      return new Mutation(result, events);
    }

    static Mutation failure(OperationResult result) {
      return new Mutation(result, List.of());
    }
  }

  @FunctionalInterface
  private interface OpWork {
    Mutation run(Connection c) throws SQLException;
  }

  private record PlayerBalance(UUID uuid, long units) {}
}
