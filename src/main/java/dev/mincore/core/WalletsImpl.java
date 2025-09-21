/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.Wallets;
import dev.mincore.api.events.CoreEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MariaDB-backed implementation of {@link Wallets}. */
public final class WalletsImpl implements Wallets {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final int EVENT_VERSION = 1;

  private final DataSource ds;
  private final EventBus events;

  /**
   * Creates a wallet service backed by the given datasource and event bus.
   *
   * @param ds pooled datasource connected to the MinCore schema
   * @param events core event bus used to emit balance change notifications
   */
  public WalletsImpl(DataSource ds, EventBus events) {
    this.ds = ds;
    this.events = events;
  }

  @Override
  public long getBalance(UUID player) {
    if (player == null) return 0L;
    String sql = "SELECT balance_units FROM players WHERE uuid=?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, uuidToBytes(player));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) getBalance failed", e);
    }
    return 0L;
  }

  @Override
  public boolean deposit(UUID player, long amount, String reason) {
    return deposit(player, amount, reason, autoKey());
  }

  @Override
  public boolean withdraw(UUID player, long amount, String reason) {
    return withdraw(player, amount, reason, autoKey());
  }

  @Override
  public boolean transfer(UUID from, UUID to, long amount, String reason) {
    return transfer(from, to, amount, reason, autoKey());
  }

  @Override
  public boolean deposit(UUID player, long amount, String reason, String idemKey) {
    if (player == null || amount < 0) return false;
    String cleanReason = clampReason(reason);
    String payload = canonical("core:deposit", null, player, amount, canonicalReason(reason));
    return applyIdempotent(
        "core:deposit", idemKey, payload, c -> applyDelta(c, player, amount, cleanReason));
  }

  @Override
  public boolean withdraw(UUID player, long amount, String reason, String idemKey) {
    if (player == null || amount < 0) return false;
    String cleanReason = clampReason(reason);
    String payload = canonical("core:withdraw", player, null, amount, canonicalReason(reason));
    return applyIdempotent(
        "core:withdraw", idemKey, payload, c -> applyDelta(c, player, -amount, cleanReason));
  }

  @Override
  public boolean transfer(UUID from, UUID to, long amount, String reason, String idemKey) {
    if (from == null || to == null || amount < 0) return false;
    String cleanReason = clampReason(reason);
    String payload = canonical("core:transfer", from, to, amount, canonicalReason(reason));
    return applyIdempotent(
        "core:transfer", idemKey, payload, c -> applyTransfer(c, from, to, amount, cleanReason));
  }

  private boolean applyDelta(Connection c, UUID player, long delta, String reason)
      throws SQLException {
    PlayerBalance before = lockBalance(c, player);
    if (before == null) {
      return false;
    }
    long newUnits = before.units + delta;
    if (newUnits < 0) {
      return false;
    }

    long now = Instant.now().getEpochSecond();
    updateBalance(c, player, newUnits, now);
    long seq = nextSeq(c, player);
    events.fireBalanceChanged(
        new CoreEvents.BalanceChangedEvent(
            player, seq, before.units, newUnits, reason, EVENT_VERSION));
    return true;
  }

  private boolean applyTransfer(Connection c, UUID from, UUID to, long amount, String reason)
      throws SQLException {
    if (from.equals(to)) {
      return true; // no-op self transfer
    }
    UUID first = compare(from, to) <= 0 ? from : to;
    UUID second = first.equals(from) ? to : from;

    PlayerBalance firstBal = lockBalance(c, first);
    PlayerBalance secondBal = lockBalance(c, second);
    PlayerBalance fromBal = first.equals(from) ? firstBal : secondBal;
    PlayerBalance toBal = first.equals(from) ? secondBal : firstBal;

    if (fromBal == null || toBal == null) {
      return false;
    }
    if (fromBal.units < amount) {
      return false;
    }

    long now = Instant.now().getEpochSecond();
    long newFrom = fromBal.units - amount;
    long newTo = toBal.units + amount;

    updateBalance(c, from, newFrom, now);
    updateBalance(c, to, newTo, now);

    long fromSeq = nextSeq(c, from);
    long toSeq = nextSeq(c, to);

    events.fireBalanceChanged(
        new CoreEvents.BalanceChangedEvent(
            from, fromSeq, fromBal.units, newFrom, reason, EVENT_VERSION));
    events.fireBalanceChanged(
        new CoreEvents.BalanceChangedEvent(to, toSeq, toBal.units, newTo, reason, EVENT_VERSION));
    return true;
  }

  private PlayerBalance lockBalance(Connection c, UUID uuid) throws SQLException {
    String sql = "SELECT balance_units FROM players WHERE uuid=? FOR UPDATE";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, uuidToBytes(uuid));
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
      ps.setBytes(3, uuidToBytes(uuid));
      ps.executeUpdate();
    }
  }

  private long nextSeq(Connection c, UUID uuid) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO player_event_seq(uuid,seq) VALUES(?,1) "
                    + "ON DUPLICATE KEY UPDATE seq=LAST_INSERT_ID(seq+1)");
        Statement last = c.createStatement()) {
      ps.setBytes(1, uuidToBytes(uuid));
      ps.executeUpdate();
      try (ResultSet rs = last.executeQuery("SELECT LAST_INSERT_ID()")) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    return 0L;
  }

  private boolean applyIdempotent(String scope, String idemKey, String payload, TxWork work) {
    String insert =
        "INSERT INTO core_requests(key_hash,scope,payload_hash,ok,created_at_s,expires_at_s) "
            + "VALUES(?, ?, ?, 0, ?, ?) ON DUPLICATE KEY UPDATE key_hash=VALUES(key_hash)";
    String select =
        "SELECT payload_hash, ok FROM core_requests WHERE key_hash=? AND scope=? FOR UPDATE";
    String markOk = "UPDATE core_requests SET ok=1 WHERE key_hash=? AND scope=?";
    long now = Instant.now().getEpochSecond();
    long exp = now + 30L * 24 * 3600;
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      byte[] keyHash = sha256(idemKey);
      byte[] payloadHash = sha256(payload);

      try (PreparedStatement ins = c.prepareStatement(insert)) {
        ins.setBytes(1, keyHash);
        ins.setString(2, scope);
        ins.setBytes(3, payloadHash);
        ins.setLong(4, now);
        ins.setLong(5, exp);
        ins.executeUpdate();
      }

      try (PreparedStatement check = c.prepareStatement(select)) {
        check.setBytes(1, keyHash);
        check.setString(2, scope);
        try (ResultSet rs = check.executeQuery()) {
          if (rs.next()) {
            byte[] ph = rs.getBytes(1);
            int ok = rs.getInt(2);
            if (!java.util.Arrays.equals(ph, payloadHash)) {
              c.rollback();
              LOG.info("(mincore) IDEMPOTENCY_MISMATCH scope={} key={}", scope, idemKey);
              return false;
            }
            if (ok == 1) {
              c.commit();
              LOG.info("(mincore) IDEMPOTENCY_REPLAY scope={} key={}", scope, idemKey);
              return true;
            }
          }
        }
      }

      boolean result;
      try {
        result = work.run(c);
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
      if (!result) {
        c.rollback();
        return false;
      }

      try (PreparedStatement done = c.prepareStatement(markOk)) {
        done.setBytes(1, keyHash);
        done.setString(2, scope);
        done.executeUpdate();
      }
      c.commit();
      return true;
    } catch (SQLException e) {
      LOG.warn("(mincore) idempotent op failed", e);
      return false;
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

  private static byte[] uuidToBytes(UUID uuid) {
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();
    byte[] out = new byte[16];
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) ((msb >>> (8 * (7 - i))) & 0xff);
    }
    for (int i = 0; i < 8; i++) {
      out[8 + i] = (byte) ((lsb >>> (8 * (7 - i))) & 0xff);
    }
    return out;
  }

  private static int compare(UUID a, UUID b) {
    int cmp = Long.compare(a.getMostSignificantBits(), b.getMostSignificantBits());
    if (cmp != 0) return cmp;
    return Long.compare(a.getLeastSignificantBits(), b.getLeastSignificantBits());
  }

  private static String autoKey() {
    return "core:auto:" + System.nanoTime();
  }

  @FunctionalInterface
  private interface TxWork {
    boolean run(Connection c) throws SQLException;
  }

  private record PlayerBalance(UUID uuid, long units) {}
}
