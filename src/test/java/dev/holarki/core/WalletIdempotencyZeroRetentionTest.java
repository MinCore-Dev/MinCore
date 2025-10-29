/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.Wallets;
import dev.holarki.api.Wallets.OperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for zero-retention idempotency configuration. */
final class WalletIdempotencyZeroRetentionTest {
  private static final String DB_NAME = "holarki_wallet_zero_retention";

  private CoreServices services;
  private Wallets wallets;
  private Path tempDir;

  @BeforeAll
  static void createDatabase() throws Exception {
    MariaDbTestSupport.ensureDatabase(DB_NAME);
  }

  @AfterAll
  static void dropDatabase() throws Exception {
    MariaDbTestSupport.dropDatabase(DB_NAME);
  }

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("wallet-zero-retention");
    Config config = TestConfigFactory.create(DB_NAME, tempDir, 0);
    services = (CoreServices) CoreServices.start(config);
    Migrations.apply(services);
    wallets = services.wallets();
    registerPlayer(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Alice");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (services != null) {
        services.shutdown();
      }
    } finally {
      wipeTables();
      if (tempDir != null) {
        TestFiles.deleteRecursively(tempDir);
        tempDir = null;
      }
    }
  }

  @Test
  void zeroRetentionStillReplaysDuplicateRequests() {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String key = "zero-retention";

    OperationResult first = wallets.depositResult(player, 1_000L, "bonus", key);
    assertTrue(first.ok());
    assertNull(first.code());

    OperationResult replay = wallets.depositResult(player, 1_000L, "bonus", key);
    assertTrue(replay.ok());
    assertEquals(ErrorCode.IDEMPOTENCY_REPLAY, replay.code());
    assertEquals(1_000L, wallets.getBalance(player));
  }

  private void registerPlayer(UUID uuid, String name) {
    services.players().upsertSeen(uuid, name, Instant.now().getEpochSecond());
  }

  private void wipeTables() throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                MariaDbTestSupport.jdbcUrl(DB_NAME),
                MariaDbTestSupport.USER,
                MariaDbTestSupport.PASSWORD);
        Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM core_ledger");
      st.executeUpdate("DELETE FROM player_attributes");
      st.executeUpdate("DELETE FROM player_event_seq");
      st.executeUpdate("DELETE FROM core_requests");
      st.executeUpdate("DELETE FROM players");
    }
  }
}
