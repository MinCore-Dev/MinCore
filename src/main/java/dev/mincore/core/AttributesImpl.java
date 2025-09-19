/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.Attributes;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MariaDB-backed implementation of {@link Attributes}.
 *
 * <p>Enforces JSON validity/size in the DB via a CHECK constraint. Keys are unique per-owner.
 */
public final class AttributesImpl implements Attributes {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private final DataSource ds;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   */
  public AttributesImpl(javax.sql.DataSource ds) {
    this.ds = ds;
  }

  @Override
  public Optional<String> get(UUID owner, String key) {
    String sql =
        "SELECT value_json FROM player_attributes WHERE owner_uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND attr_key=?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, owner.toString());
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.ofNullable(rs.getString(1));
      }
    } catch (SQLException e) {
      LOG.warn("(mincore) attr.get failed", e);
    }
    return Optional.empty();
  }

  @Override
  public void put(UUID owner, String key, String jsonValue, long nowS) {
    String sql =
        "INSERT INTO player_attributes(owner_uuid,attr_key,value_json,created_at_s,updated_at_s) "
            + "VALUES(UNHEX(REPLACE(?, \"-\", \"\")),?,?,?,?) ON DUPLICATE KEY UPDATE value_json=VALUES(value_json), updated_at_s=VALUES(updated_at_s)";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, owner.toString());
      ps.setString(2, key);
      ps.setString(3, jsonValue);
      ps.setLong(4, nowS);
      ps.setLong(5, nowS);
      ps.executeUpdate();
    } catch (SQLException e) {
      LOG.warn("(mincore) attr.put failed", e);
    }
  }

  @Override
  public void remove(UUID owner, String key) {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "DELETE FROM player_attributes WHERE owner_uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND attr_key=?")) {
      ps.setString(1, owner.toString());
      ps.setString(2, key);
      ps.executeUpdate();
    } catch (SQLException e) {
      LOG.warn("(mincore) attr.remove failed", e);
    }
  }
}
