/* Holarki © 2025 — MIT */
package dev.holarki.core;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.holarki.api.Attributes;
import dev.holarki.api.ErrorCode;
import dev.holarki.util.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final int MAX_JSON_CHARS = 8192;
  private final DataSource ds;
  private final DbHealth dbHealth;
  private final Metrics metrics;

  /**
   * Creates a new instance.
   *
   * @param ds shared datasource
   * @param dbHealth health monitor for degraded mode handling
   */
  public AttributesImpl(javax.sql.DataSource ds, DbHealth dbHealth, Metrics metrics) {
    this.ds = ds;
    this.dbHealth = dbHealth;
    this.metrics = metrics;
  }

  @Override
  public Optional<String> get(UUID owner, String key) {
    String sql = "SELECT value_json FROM player_attributes WHERE owner_uuid=? AND attr_key=?";
    Optional<String> result = Optional.empty();
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(owner));
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          result = Optional.ofNullable(rs.getString(1));
        }
      }
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordAttributeRead(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      if (metrics != null) {
        metrics.recordAttributeRead(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
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
      if (metrics != null) {
        metrics.recordAttributeWrite(false, ErrorCode.DEGRADED_MODE);
      }
      return;
    }
    String sql =
        "INSERT INTO player_attributes(owner_uuid,attr_key,value_json,created_at_s,updated_at_s) "
            + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE value_json=VALUES(value_json), updated_at_s=VALUES(updated_at_s)";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBytes(1, Uuids.toBytes(owner));
      ps.setString(2, key);
      ps.setString(3, sanitizeJson(jsonValue));
      ps.setLong(4, nowS);
      ps.setLong(5, nowS);
      ps.executeUpdate();
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordAttributeWrite(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      if (metrics != null) {
        metrics.recordAttributeWrite(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
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
      if (metrics != null) {
        metrics.recordAttributeWrite(false, ErrorCode.DEGRADED_MODE);
      }
      return;
    }
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("DELETE FROM player_attributes WHERE owner_uuid=? AND attr_key=?")) {
      ps.setBytes(1, Uuids.toBytes(owner));
      ps.setString(2, key);
      ps.executeUpdate();
      dbHealth.markSuccess();
      if (metrics != null) {
        metrics.recordAttributeWrite(true, null);
      }
    } catch (SQLException e) {
      ErrorCode code = SqlErrorCodes.classify(e);
      dbHealth.markFailure(e);
      if (metrics != null) {
        metrics.recordAttributeWrite(false, code);
      }
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          "attributes.remove",
          e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
  }

  private static String sanitizeJson(String jsonValue) {
    if (jsonValue == null) {
      throw new IllegalArgumentException("jsonValue must not be null");
    }
    String trimmed = jsonValue.trim();
    if (trimmed.length() > MAX_JSON_CHARS) {
      throw new IllegalArgumentException("jsonValue exceeds 8192 characters");
    }
    try {
      JsonParser.parseString(trimmed);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("jsonValue must be valid JSON", e);
    }
    return trimmed;
  }
}
