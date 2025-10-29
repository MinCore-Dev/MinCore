/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Integration tests covering wallet idempotency edge cases. */
final class WalletIdempotencyTest {
  private static final String DB_NAME = "holarki_wallet_test";

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
    tempDir = Files.createTempDirectory("wallet-it");
    Config config = TestConfigFactory.create(DB_NAME, tempDir);
    services = (CoreServices) CoreServices.start(config);
    Migrations.apply(services);
    wallets = services.wallets();
    registerPlayer(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Alice");
    registerPlayer(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Bob");
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
  void depositReplayReturnsSemanticCode() {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String key = "test-key";
    OperationResult first = wallets.depositResult(player, 1_000L, "bonus", key);
    assertTrue(first.ok());
    OperationResult replay = wallets.depositResult(player, 1_000L, "bonus", key);
    assertTrue(replay.ok());
    assertEquals(ErrorCode.IDEMPOTENCY_REPLAY, replay.code());
    assertEquals(1_000L, wallets.getBalance(player));
  }

  @Test
  void depositMismatchFailsWithErrorCode() {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String key = "mismatch-key";
    OperationResult first = wallets.depositResult(player, 2_000L, "event", key);
    assertTrue(first.ok());
    OperationResult mismatch = wallets.depositResult(player, 3_000L, "event", key);
    assertFalse(mismatch.ok());
    assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, mismatch.code());
    assertEquals(2_000L, wallets.getBalance(player));
  }

  @Test
  void transferReplayIsIdempotentAcrossPlayers() {
    UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");
    wallets.depositResult(alice, 5_000L, "seed", "seed-key");
    String key = "transfer-key";
    OperationResult first = wallets.transferResult(alice, bob, 1_500L, "gift", key);
    assertTrue(first.ok());
    OperationResult replay = wallets.transferResult(alice, bob, 1_500L, "gift", key);
    assertTrue(replay.ok());
    assertEquals(ErrorCode.IDEMPOTENCY_REPLAY, replay.code());
    assertEquals(3_500L, wallets.getBalance(alice));
    assertEquals(1_500L, wallets.getBalance(bob));
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
