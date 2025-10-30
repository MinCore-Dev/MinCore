/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.Wallets;
import dev.holarki.api.Wallets.OperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
  void expiredKeyTreatsRequestAsNew() throws Exception {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String key = "expired-key";
    OperationResult first = wallets.depositResult(player, 750L, "expiry", key);
    assertTrue(first.ok());

    long now = Instant.now().getEpochSecond();
    long expiredCreated = now - 120;
    long expiredAt = now - 30;
    try (Connection c =
            DriverManager.getConnection(
                MariaDbTestSupport.jdbcUrl(DB_NAME),
                MariaDbTestSupport.USER,
                MariaDbTestSupport.PASSWORD);
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE core_requests SET created_at_s=?, expires_at_s=? WHERE scope=? AND key_hash=?")) {
      ps.setLong(1, expiredCreated);
      ps.setLong(2, expiredAt);
      ps.setString(3, "core:deposit");
      ps.setBytes(4, sha256(key));
      ps.executeUpdate();
    }

    OperationResult second = wallets.depositResult(player, 1_250L, "expiry", key);
    assertTrue(second.ok());
    assertNull(second.code());
    assertEquals(2_000L, wallets.getBalance(player));

    long refreshedExpires;
    try (Connection c =
            DriverManager.getConnection(
                MariaDbTestSupport.jdbcUrl(DB_NAME),
                MariaDbTestSupport.USER,
                MariaDbTestSupport.PASSWORD);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT expires_at_s FROM core_requests WHERE scope=? AND key_hash=?")) {
      ps.setString(1, "core:deposit");
      ps.setBytes(2, sha256(key));
      try (var rs = ps.executeQuery()) {
        assertTrue(rs.next());
        refreshedExpires = rs.getLong(1);
      }
    }
    assertTrue(refreshedExpires > now, "expiry should be refreshed into the future");
  }

  @Test
  void depositWithNullKeyDoesNotReplay() {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    OperationResult first = wallets.depositResult(player, 500L, "null-key", null);
    assertTrue(first.ok());
    OperationResult second = wallets.depositResult(player, 500L, "null-key", null);
    assertTrue(second.ok());
    assertNull(second.code());
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

  @Test
  void concurrentRequestsWithSameKeyOnlyApplyOnce() throws Exception {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String key = "concurrency-key";
    int attempts = 6;

    ExecutorService executor = Executors.newFixedThreadPool(attempts);
    CountDownLatch ready = new CountDownLatch(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<OperationResult>> futures = new ArrayList<>(attempts);
    try {
      for (int i = 0; i < attempts; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return wallets.depositResult(player, 1_000L, "race", key);
                }));
      }

      if (!ready.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("workers did not become ready in time");
      }
      start.countDown();

      List<OperationResult> results = new ArrayList<>(attempts);
      for (Future<OperationResult> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }

      long applied = results.stream().filter(r -> r.ok() && r.code() == null).count();
      long replays =
          results.stream().filter(r -> ErrorCode.IDEMPOTENCY_REPLAY.equals(r.code())).count();

      assertEquals(1, applied, "exactly one request should mutate state");
      assertEquals(attempts - 1, replays, "remaining requests should report a replay");
      assertEquals(1_000L, wallets.getBalance(player));
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
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

  private static byte[] sha256(String input) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
