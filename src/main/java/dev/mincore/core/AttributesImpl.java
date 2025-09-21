/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.Attributes;
import dev.mincore.api.ErrorCode;
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
  private final DbHealth dbHealth;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   */
  public AttributesImpl(javax.sql.DataSource ds, DbHealth dbHealth) {
    this.ds = ds;
    this.dbHealth = dbHealth;
  }

  @Override
  public Optional<String> get(UUID owner, String key) {
    String sql =
        "SELECT value_json FROM player_attributes WHERE owner_uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND attr_key=?";
    Optional<String> result = Optional.empty();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, owner.toString());
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          result = Optional.ofNullable(rs.getString(1));
        }
      }
      dbHealth.markSuccess();
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      LOG.warn(
          "(mincore) code={} op={} message={} sqlState={} vendor={}",
          code,
          "attributes.get",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    return result;
  }

  @Override
  public void put(UUID owner, String key, String jsonValue, long nowS) {
    if (!dbHealth.allowWrite("attributes.put")) {
      return;
    }
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
      dbHealth.markSuccess();
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      LOG.warn(
          "(mincore) code={} op={} message={} sqlState={} vendor={}",
          code,
          "attributes.put",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
  }

  @Override
  public void remove(UUID owner, String key) {
    if (!dbHealth.allowWrite("attributes.remove")) {
      return;
    }
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "DELETE FROM player_attributes WHERE owner_uuid=UNHEX(REPLACE(?, \"-\", \"\")) AND attr_key=?")) {
      ps.setString(1, owner.toString());
      ps.setString(2, key);
      ps.executeUpdate();
      dbHealth.markSuccess();
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      LOG.warn(
          "(mincore) code={} op={} message={} sqlState={} vendor={}",
          code,
          "attributes.remove",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
  }
}
