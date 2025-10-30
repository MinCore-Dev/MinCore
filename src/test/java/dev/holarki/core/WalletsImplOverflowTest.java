/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import dev.holarki.api.ErrorCode;
import dev.holarki.api.Wallets.OperationResult;
import dev.holarki.util.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mariadb.jdbc.MariaDbDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class WalletsImplOverflowTest {
  private static final String OVERFLOW_MESSAGE = "balance would overflow";

  private DB db;
  private DataSource dataSource;
  private EventBus events;
  private Metrics metrics;
  private DbHealth dbHealth;
  private WalletsImpl wallets;

  @BeforeAll
  void setUp() throws Exception {
    DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
    builder.setPort(0);
    builder.addArg("--user=root");
    db = DB.newEmbeddedDB(builder.build());
    db.start();
    String database = "test";

    MariaDbDataSource maria = new MariaDbDataSource();
    maria.setUrl("jdbc:mariadb://127.0.0.1:" + db.getConfiguration().getPort() + "/" + database);
    maria.setUser("root");
    maria.setPassword("");
    this.dataSource = maria;

    createTables();

    this.events = new EventBus();
    this.metrics = org.mockito.Mockito.mock(Metrics.class);
    this.dbHealth = org.mockito.Mockito.mock(DbHealth.class);
    when(dbHealth.allowWrite(anyString())).thenReturn(true);

    this.wallets = new WalletsImpl(dataSource, events, dbHealth, metrics, Duration.ofMinutes(5));
  }

  @AfterAll
  void tearDown() throws Exception {
    if (events != null) {
      events.close();
    }
    if (db != null) {
      db.stop();
    }
  }

  @BeforeEach
  void cleanDatabase() throws Exception {
    reset(dbHealth, metrics);
    when(dbHealth.allowWrite(anyString())).thenReturn(true);
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM core_requests");
      st.execute("DELETE FROM player_event_seq");
      st.execute("DELETE FROM players");
    }
  }

  @Test
  void depositFailsFastOnOverflow() throws Exception {
    UUID player = UUID.randomUUID();
    long updatedAt = 1_000L;
    insertPlayer(player, Long.MAX_VALUE, updatedAt);

    OperationResult result = wallets.depositResult(player, 1L, "overflow", "idem-deposit-overflow");

    assertFalse(result.ok());
    assertEquals(ErrorCode.INVALID_AMOUNT, result.code());
    assertEquals(OVERFLOW_MESSAGE, result.message());
    assertBalance(player, Long.MAX_VALUE, updatedAt);
    verify(metrics).recordWalletOperation(anyString(), any());
  }

  @Test
  void transferFailsFastWhenRecipientWouldOverflow() throws Exception {
    UUID from = UUID.randomUUID();
    UUID to = UUID.randomUUID();
    long fromUpdated = 2_000L;
    long toUpdated = 3_000L;
    insertPlayer(from, 50L, fromUpdated);
    insertPlayer(to, Long.MAX_VALUE, toUpdated);

    OperationResult result =
        wallets.transferResult(from, to, 1L, "overflow", "idem-transfer-overflow");

    assertFalse(result.ok());
    assertEquals(ErrorCode.INVALID_AMOUNT, result.code());
    assertEquals(OVERFLOW_MESSAGE, result.message());
    assertBalance(from, 50L, fromUpdated);
    assertBalance(to, Long.MAX_VALUE, toUpdated);
  }

  private void createTables() throws SQLException {
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS players ("
              + "uuid BINARY(16) PRIMARY KEY,"
              + "name VARCHAR(32) NOT NULL,"
              + "balance_units BIGINT NOT NULL,"
              + "created_at_s BIGINT UNSIGNED NOT NULL,"
              + "updated_at_s BIGINT UNSIGNED NOT NULL,"
              + "seen_at_s BIGINT UNSIGNED NULL"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      st.execute(
          "CREATE TABLE IF NOT EXISTS player_event_seq ("
              + "uuid BINARY(16) PRIMARY KEY,"
              + "seq BIGINT UNSIGNED NOT NULL"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      st.execute(
          "CREATE TABLE IF NOT EXISTS core_requests ("
              + "scope VARCHAR(64) NOT NULL,"
              + "key_hash BINARY(32) NOT NULL,"
              + "payload_hash BINARY(32) NOT NULL,"
              + "ok TINYINT(1) NOT NULL DEFAULT 0,"
              + "created_at_s BIGINT UNSIGNED NOT NULL,"
              + "expires_at_s BIGINT UNSIGNED NOT NULL,"
              + "PRIMARY KEY (scope, key_hash)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  private void insertPlayer(UUID player, long balance, long updatedAt) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO players(uuid, name, balance_units, created_at_s, updated_at_s)"
                    + " VALUES(?, ?, ?, ?, ?)")) {
      ps.setBytes(1, Uuids.toBytes(player));
      ps.setString(2, "Player");
      ps.setLong(3, balance);
      ps.setLong(4, updatedAt);
      ps.setLong(5, updatedAt);
      ps.executeUpdate();
    }
  }

  private void assertBalance(UUID player, long expectedBalance, long expectedUpdatedAt)
      throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT balance_units, updated_at_s FROM players WHERE uuid=?")) {
      ps.setBytes(1, Uuids.toBytes(player));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(expectedBalance, rs.getLong(1));
        assertEquals(expectedUpdatedAt, rs.getLong(2));
      }
    }
  }
}
