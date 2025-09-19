/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.Players;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MariaDB-backed implementation of {@link Players}.
 *
 * <p>Maintains a generated lowercase name column for faster case-insensitive name lookups.
 */
public final class PlayersImpl implements Players {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private final DataSource ds;
  private final EventBus events;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   * @param events event bus
   */
  public PlayersImpl(javax.sql.DataSource ds, EventBus events) {
    this.ds = ds;
    this.events = events;
  }

  @Override
  public void ensureAccount(UUID uuid, String name) {
    String sql =
        "INSERT INTO players(uuid,name,created_at_s,updated_at_s,seen_at_s) VALUES(UNHEX(REPLACE(?, \"-\", \"\")), ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE name=VALUES(name), seen_at_s=VALUES(seen_at_s), updated_at_s=VALUES(updated_at_s)";
    long now = Instant.now().getEpochSecond();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      c.setAutoCommit(true);
      ps.setString(1, uuid.toString());
      ps.setString(2, name);
      ps.setLong(3, now);
      ps.setLong(4, now);
      ps.setLong(5, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      LOG.warn("(mincore) ensureAccount failed", e);
    }
  }

  @Override
  public void syncName(UUID uuid, String name) {
    String sql =
        "UPDATE players SET name=?, updated_at_s=? WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND name<>?";
    long now = Instant.now().getEpochSecond();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      c.setAutoCommit(true);
      ps.setString(1, name);
      ps.setLong(2, now);
      ps.setString(3, uuid.toString());
      ps.setString(4, name);
      ps.executeUpdate();
    } catch (SQLException e) {
      LOG.warn("(mincore) syncName failed", e);
    }
  }

  @Override
  public Optional<PlayerView> getPlayer(UUID uuid) {
    String sql =
        "SELECT name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE uuid=UNHEX(REPLACE(?, \"-\", \"\"))";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, uuid.toString());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String name = rs.getString(1);
          long created = rs.getLong(2);
          long updated = rs.getLong(3);
          long seen = rs.getLong(4);
          long bal = rs.getLong(5);
          final boolean wasNull = rs.wasNull();
          return Optional.of(
              new PlayerView() {
                public UUID uuid() {
                  return uuid;
                }

                public String name() {
                  return name;
                }

                public long createdAtEpochSeconds() {
                  return created;
                }

                public long updatedAtEpochSeconds() {
                  return updated;
                }

                public Long seenAtEpochSeconds() {
                  return wasNull ? null : seen;
                }

                public long balanceUnits() {
                  return bal;
                }
              });
        }
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) getPlayer failed", e);
    }
    return Optional.empty();
  }

  @Override
  public Optional<PlayerView> getPlayerByName(String exactName) {
    String sql =
        "SELECT uuid,created_at_s,updated_at_s,seen_at_s,balance_units FROM players WHERE name_lower=?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, exactName.toLowerCase());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          byte[] u = rs.getBytes(1);
          long created = rs.getLong(2);
          long updated = rs.getLong(3);
          long seen = rs.getLong(4);
          long bal = rs.getLong(5);
          final boolean wasNull = rs.wasNull();
          return Optional.of(
              new PlayerView() {
                public java.util.UUID uuid() {
                  return readUuid(u);
                }

                public String name() {
                  return exactName;
                }

                public long createdAtEpochSeconds() {
                  return created;
                }

                public long updatedAtEpochSeconds() {
                  return updated;
                }

                public Long seenAtEpochSeconds() {
                  return wasNull ? null : seen;
                }

                public long balanceUnits() {
                  return bal;
                }
              });
        }
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) getPlayerByName failed", e);
    }
    return Optional.empty();
  }

  @Override
  public void iteratePlayers(Consumer<PlayerView> consumer, int batchSize) {
    String sql =
        "SELECT uuid,name,created_at_s,updated_at_s,seen_at_s,balance_units FROM players ORDER BY uuid LIMIT ? OFFSET ?";
    int offset = 0, n;
    do {
      n = 0;
      try (Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setInt(1, batchSize);
        ps.setInt(2, offset);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            n++;
            byte[] u = rs.getBytes(1);
            String name = rs.getString(2);
            long created = rs.getLong(3);
            long updated = rs.getLong(4);
            long seen = rs.getLong(5);
            long bal = rs.getLong(6);
            final boolean wasNull = rs.wasNull();
            consumer.accept(
                new PlayerView() {
                  public java.util.UUID uuid() {
                    return readUuid(u);
                  }

                  public String name() {
                    return name;
                  }

                  public long createdAtEpochSeconds() {
                    return created;
                  }

                  public long updatedAtEpochSeconds() {
                    return updated;
                  }

                  public Long seenAtEpochSeconds() {
                    return wasNull ? null : seen;
                  }

                  public long balanceUnits() {
                    return bal;
                  }
                });
          }
        }
      } catch (SQLException e) {
        LOG.warn("(mincore) iteratePlayers failed", e);
        break;
      }
      offset += n;
    } while (n == batchSize);
  }

  /** Converts a 16-byte binary UUID to a Java {@link java.util.UUID}. */
  private static java.util.UUID readUuid(byte[] b) {
    if (b == null || b.length != 16) return null;
    long msb = 0, lsb = 0;
    for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xff);
    for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xff);
    return new java.util.UUID(msb, lsb);
  }
}
