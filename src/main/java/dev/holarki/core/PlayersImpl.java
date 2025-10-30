/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.Players;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.util.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MariaDB-backed implementation of {@link Players}. */
public final class PlayersImpl implements Players {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final int EVENT_VERSION = 1;

  private final DataSource ds;
  private final EventBus events;
  private final DbHealth dbHealth;
  private final Metrics metrics;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   * @param events event bus for player lifecycle notifications
   * @param dbHealth health monitor for degraded mode handling
   */
  public PlayersImpl(DataSource ds, EventBus events, DbHealth dbHealth, Metrics metrics) {
    this.ds = ds;
    this.events = events;
    this.dbHealth = dbHealth;
    this.metrics = metrics;
  }

  @Override
  public Optional<PlayerRef> byUuid(UUID uuid) {
    if (uuid == null) return Optional.empty();
    String sql =
        "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE uuid=?";
    Optional<PlayerRef> result = Optional.empty();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(uuid));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          result = Optional.of(mapPlayer(rs));
        }
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordPlayerLookup(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerLookup(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.byUuid",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return result;
  }

  @Override
  public Map<UUID, PlayerRef> byUuidBulk(Collection<UUID> uuids) {
    if (uuids == null || uuids.isEmpty()) {
      return Map.of();
    }
    LinkedHashSet<UUID> unique = new LinkedHashSet<>();
    for (UUID uuid : uuids) {
      if (uuid != null) {
        unique.add(uuid);
      }
    }
    if (unique.isEmpty()) {
      return Map.of();
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE uuid IN (");
    int paramCount = unique.size();
    for (int i = 0; i < paramCount; i++) {
      if (i > 0) {
        sql.append(',');
      }
      sql.append('?');
    }
    sql.append(')');
    Map<UUID, PlayerRef> out = new LinkedHashMap<>();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql.toString())) {
      int idx = 1;
      for (UUID uuid : unique) {
        ps.setBytes(idx++, Uuids.toBytes(uuid));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          PlayerRef ref = mapPlayer(rs);
          if (ref != null && ref.uuid() != null) {
            out.put(ref.uuid(), ref);
          }
        }
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordPlayerLookup(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerLookup(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.byUuidBulk",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return Map.copyOf(out);
  }

  @Override
  public Optional<PlayerRef> byName(String name) {
    if (name == null || name.isBlank()) return Optional.empty();
    String sql =
        "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE name_lower=?";
    Optional<PlayerRef> result = Optional.empty();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, normalizeNameKey(name));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          result = Optional.of(mapPlayer(rs));
        }
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordPlayerLookup(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerLookup(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.byName",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return result;
  }

  @Override
  public List<PlayerRef> byNameAll(String name) {
    List<PlayerRef> out = new ArrayList<>();
    if (name == null || name.isBlank()) {
      return List.of();
    }
    String sql =
        "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE name_lower=? ORDER BY uuid";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, normalizeNameKey(name));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(mapPlayer(rs));
        }
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordPlayerLookup(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerLookup(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.byNameAll",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return List.copyOf(out);
  }

  @Override
  public void upsertSeen(UUID uuid, String name, long seenAtS) {
    if (uuid == null) return;
    if (!dbHealth.allowWrite("players.upsertSeen")) {
      if (metrics != null) {
        metrics.recordPlayerMutation(false, ErrorCode.DEGRADED_MODE);
      }
      return;
    }
    String cleanName = sanitizeName(name);
    Long seen = seenAtS > 0 ? seenAtS : null;
    long now = Instant.now().getEpochSecond();

    try (Connection c = ds.getConnection()) {
      try {
        c.setAutoCommit(false);
        PlayerSnapshot before = lockPlayer(c, uuid);
        if (before == null) {
          insertPlayer(c, uuid, cleanName, seen, now);
          long seq = nextSeq(c, uuid);
          c.commit();
          dbHealth.markSuccess();
          if (metrics != null) {
            metrics.recordPlayerMutation(true, null);
          }
          events.firePlayerRegistered(
              new CoreEvents.PlayerRegisteredEvent(uuid, seq, cleanName, EVENT_VERSION));
          return;
        }

        boolean nameChanged = !before.name().equals(cleanName);
        boolean seenChanged = !equalsNullable(before.seenAt(), seen);
        if (!nameChanged && !seenChanged) {
          c.commit();
          dbHealth.markSuccess();
          return;
        }

        updatePlayer(c, uuid, cleanName, seen, now);
        long seq = nextSeq(c, uuid);
        c.commit();
        dbHealth.markSuccess();
        if (metrics != null) {
          metrics.recordPlayerMutation(true, null);
        }
        events.firePlayerSeenUpdated(
            new CoreEvents.PlayerSeenUpdatedEvent(
                uuid,
                seq,
                before.name(),
                cleanName,
                seen != null ? seen : (before.seenAt() != null ? before.seenAt() : 0L),
                EVENT_VERSION));
      } catch (SQLException e) {
        try {
          c.rollback();
        } catch (SQLException ignore) {
        }
        ErrorCode code = SqlErrorCodes.classify(e);
        if (code == ErrorCode.CONNECTION_LOST) {
          dbHealth.markFailure(e);
        } else {
          dbHealth.markSuccess();
        }
        throw e;
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerMutation(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.upsertSeen",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
  }

  @Override
  public void iteratePlayers(Consumer<PlayerRef> consumer) {
    if (consumer == null) return;
    String sql =
        "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players ORDER BY uuid";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        consumer.accept(mapPlayer(rs));
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordPlayerLookup(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      if (code == ErrorCode.CONNECTION_LOST) {
        dbHealth.markFailure(e);
      } else {
        dbHealth.markSuccess();
      }
      if (metrics != null) {
        metrics.recordPlayerLookup(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "players.iteratePlayers",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
  }

  private PlayerSnapshot lockPlayer(Connection c, UUID uuid) throws SQLException {
    String sql = "SELECT name,seen_at_s FROM players WHERE uuid=? FOR UPDATE";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(uuid));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String name = rs.getString("name");
          long seenRaw = rs.getLong("seen_at_s");
          boolean seenNull = rs.wasNull();
          return new PlayerSnapshot(name, seenNull ? null : seenRaw);
        }
      }
    }
    return null;
  }

  private void insertPlayer(Connection c, UUID uuid, String name, Long seenAt, long now)
      throws SQLException {
    String sql =
        "INSERT INTO players(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s) VALUES(?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(uuid));
      ps.setString(2, name);
      ps.setLong(3, 0L);
      ps.setLong(4, now);
      ps.setLong(5, now);
      if (seenAt == null) {
        ps.setNull(6, Types.BIGINT);
      } else {
        ps.setLong(6, seenAt);
      }
      ps.executeUpdate();
    }
  }

  private void updatePlayer(Connection c, UUID uuid, String name, Long seenAt, long now)
      throws SQLException {
    String sql = "UPDATE players SET name=?, updated_at_s=?, seen_at_s=? WHERE uuid=?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, name);
      ps.setLong(2, now);
      if (seenAt == null) {
        ps.setNull(3, Types.BIGINT);
      } else {
        ps.setLong(3, seenAt);
      }
      ps.setBytes(4, Uuids.toBytes(uuid));
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

  private static PlayerRef mapPlayer(ResultSet rs) throws SQLException {
    byte[] raw = rs.getBytes("uuid");
    UUID uuid = raw == null ? null : bytesToUuid(raw);
    String name = rs.getString("name");
    long created = rs.getLong("created_at_s");
    long updated = rs.getLong("updated_at_s");
    long seenRaw = rs.getLong("seen_at_s");
    boolean seenNull = rs.wasNull();
    long balance = rs.getLong("balance_units");
    Long seen = seenNull ? null : seenRaw;
    return new DbPlayer(uuid, name, created, updated, seen, balance);
  }

  private static UUID bytesToUuid(byte[] data) {
    if (data == null || data.length != 16) return null;
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (data[i] & 0xffL);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (data[i] & 0xffL);
    }
    return new UUID(msb, lsb);
  }

  private static String normalizeNameKey(String name) {
    return sanitizeName(name).toLowerCase(Locale.ROOT);
  }

  private static String sanitizeName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      return "unknown";
    }
    return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
  }

  private static boolean equalsNullable(Long a, Long b) {
    return a == null ? b == null : a.equals(b);
  }

  private record PlayerSnapshot(String name, Long seenAt) {}

  private record DbPlayer(
      UUID uuid, String name, long createdAtS, long updatedAtS, Long seenAtS, long balanceUnits)
      implements PlayerRef {}
}
