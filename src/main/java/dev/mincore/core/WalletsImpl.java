/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.Wallets;
import dev.mincore.api.events.CoreEvents;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MariaDB-backed implementation of {@link Wallets}.
 *
 * <p>Implements idempotent balance operations using the {@code core_requests} table. Each logical
 * operation must provide (or is given) an idempotency key; replays of the same key + payload are
 * accepted without side-effects; a different payload for the same key is rejected.
 */
public final class WalletsImpl implements Wallets {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private final DataSource ds;
  private final EventBus events;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   * @param events event bus
   */
  public WalletsImpl(DataSource ds, EventBus events) {
    this.ds = ds;
    this.events = events;
  }

  @Override
  public long getBalance(UUID player) {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT balance_units FROM players WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\"))")) {
      ps.setString(1, player.toString());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) getBalance failed", e);
      return 0L;
    }
  }

  @Override
  public boolean deposit(UUID player, long amount, String reason) {
    return deposit(player, amount, reason, "core:auto:" + System.nanoTime());
  }

  @Override
  public boolean withdraw(UUID player, long amount, String reason) {
    return withdraw(player, amount, reason, "core:auto:" + System.nanoTime());
  }

  @Override
  public boolean transfer(UUID from, UUID to, long amount, String reason) {
    return transfer(from, to, amount, reason, "core:auto:" + System.nanoTime());
  }

  /** Computes a SHA-256 digest of a string. */
  private static byte[] sha256(String s) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Canonicalizes operation payload for idempotency hashing.
   *
   * @param scope logical operation scope
   * @param from sender or {@code null}
   * @param to recipient or {@code null}
   * @param amount amount in units
   * @param reason normalized reason string
   */
  private static String canonical(String scope, UUID from, UUID to, long amount, String reason) {
    String r = reason == null ? "" : reason.trim().toLowerCase();
    if (r.length() > 64) r = r.substring(0, 64);
    return scope
        + "|"
        + (from == null ? "00000000-0000-0000-0000-000000000000" : from.toString())
        + "|"
        + (to == null ? "00000000-0000-0000-0000-000000000000" : to.toString())
        + "|"
        + Long.toString(amount)
        + "|"
        + r;
  }

  @Override
  public boolean deposit(UUID player, long amount, String reason, String idemKey) {
    if (amount < 0) return false;
    String scope = "core:deposit";
    String payload = canonical(scope, null, player, amount, reason);
    return applyIdempotent(
        scope,
        idemKey,
        payload,
        c -> {
          try (PreparedStatement seq =
              c.prepareStatement(
                  "INSERT INTO player_event_seq(uuid,seq) VALUES(UNHEX(REPLACE(?, \"-\", \"\")),1) ON DUPLICATE KEY UPDATE seq=LAST_INSERT_ID(seq+1)")) {
            seq.setString(1, player.toString());
            seq.executeUpdate();
          }
          long lastId;
          try (ResultSet rs = c.createStatement().executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            lastId = rs.getLong(1);
          }

          try (PreparedStatement upd =
              c.prepareStatement(
                  "UPDATE players SET balance_units=balance_units+?, updated_at_s=? WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\"))")) {
            upd.setLong(1, amount);
            upd.setLong(2, Instant.now().getEpochSecond());
            upd.setString(3, player.toString());
            upd.executeUpdate();
          }
          events.fireBalanceChanged(
              new CoreEvents.BalanceChangedEvent(player, -1, -1, reason, lastId, 1));
          return true;
        });
  }

  @Override
  public boolean withdraw(UUID player, long amount, String reason, String idemKey) {
    if (amount < 0) return false;
    String scope = "core:withdraw";
    String payload = canonical(scope, player, null, amount, reason);
    return applyIdempotent(
        scope,
        idemKey,
        payload,
        c -> {
          try (PreparedStatement seq =
              c.prepareStatement(
                  "INSERT INTO player_event_seq(uuid,seq) VALUES(UNHEX(REPLACE(?, \"-\", \"\")),1) ON DUPLICATE KEY UPDATE seq=LAST_INSERT_ID(seq+1)")) {
            seq.setString(1, player.toString());
            seq.executeUpdate();
          }
          long lastId;
          try (ResultSet rs = c.createStatement().executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            lastId = rs.getLong(1);
          }

          try (PreparedStatement upd =
              c.prepareStatement(
                  "UPDATE players SET balance_units=balance_units-?, updated_at_s=? WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND balance_units>=?")) {
            upd.setLong(1, amount);
            upd.setLong(2, Instant.now().getEpochSecond());
            upd.setString(3, player.toString());
            upd.setLong(4, amount);
            if (upd.executeUpdate() != 1) return false;
          }
          events.fireBalanceChanged(
              new CoreEvents.BalanceChangedEvent(player, -1, -1, reason, lastId, 1));
          return true;
        });
  }

  @Override
  public boolean transfer(UUID from, UUID to, long amount, String reason, String idemKey) {
    if (amount < 0) return false;
    String scope = "shop:transfer";
    String payload = canonical(scope, from, to, amount, reason);
    return applyIdempotent(
        scope,
        idemKey,
        payload,
        c -> {
          try (PreparedStatement seq =
              c.prepareStatement(
                  "INSERT INTO player_event_seq(uuid,seq) VALUES(UNHEX(REPLACE(?, \"-\", \"\")),1) ON DUPLICATE KEY UPDATE seq=LAST_INSERT_ID(seq+1)")) {
            seq.setString(1, from.toString());
            seq.executeUpdate();
          }
          long lastId;
          try (ResultSet rs = c.createStatement().executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            lastId = rs.getLong(1);
          }

          try (PreparedStatement w =
              c.prepareStatement(
                  "UPDATE players SET balance_units=balance_units-?, updated_at_s=? WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND balance_units>=?")) {
            w.setLong(1, amount);
            w.setLong(2, Instant.now().getEpochSecond());
            w.setString(3, from.toString());
            w.setLong(4, amount);
            if (w.executeUpdate() != 1) return false;
          }
          try (PreparedStatement d =
              c.prepareStatement(
                  "UPDATE players SET balance_units=balance_units+?, updated_at_s=? WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\"))")) {
            d.setLong(1, amount);
            d.setLong(2, Instant.now().getEpochSecond());
            d.setString(3, to.toString());
            d.executeUpdate();
          }
          events.fireBalanceChanged(
              new CoreEvents.BalanceChangedEvent(from, -1, -1, reason, lastId, 1));
          events.fireBalanceChanged(
              new CoreEvents.BalanceChangedEvent(to, -1, -1, reason, lastId, 1));
          return true;
        });
  }

  /**
   * Wraps a unit of work in idempotency bookkeeping using {@code core_requests}.
   *
   * @param scope operation scope (e.g., {@code core:deposit})
   * @param idemKey idempotency key
   * @param payload canonical payload string
   * @param work transactional work
   * @return success status
   */
  private boolean applyIdempotent(String scope, String idemKey, String payload, TxWork work) {
    String insert =
        "INSERT INTO core_requests(key_hash,scope,payload_hash,ok,created_at_s,expires_at_s) VALUES(?, ?, ?, 0, ?, ?) ON DUPLICATE KEY UPDATE key_hash=VALUES(key_hash)";
    String select =
        "SELECT payload_hash, ok FROM core_requests WHERE key_hash=? AND scope=? FOR UPDATE";
    String markOk = "UPDATE core_requests SET ok=1 WHERE key_hash=? AND scope=?";
    long now = java.time.Instant.now().getEpochSecond();
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
              LOG.info("(mincore) IDEMPOTENCY_MISMATCH");
              return false;
            }
            if (ok == 1) {
              c.commit();
              LOG.info("(mincore) IDEMPOTENCY_REPLAY");
              return true;
            }
          }
        }
      }
      boolean res = work.run(c);
      if (!res) {
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

  /** Functional unit of transactional work. */
  @FunctionalInterface
  interface TxWork {
    boolean run(Connection c) throws SQLException;
  }
}
