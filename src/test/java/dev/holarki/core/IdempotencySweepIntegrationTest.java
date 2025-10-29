/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.Wallets;
import dev.holarki.modules.scheduler.SchedulerEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration test covering scheduler-driven idempotency cleanup. */
final class IdempotencySweepIntegrationTest {
  private static final String DB_NAME = "holarki_sweep_test";

  private CoreServices services;
  private Wallets wallets;
  private Config config;
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
    tempDir = Files.createTempDirectory("sweep-it");
    config = TestConfigFactory.create(DB_NAME, tempDir);
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
  void sweepRemovesExpiredRowsAndKeepsFreshOnes() throws Exception {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    wallets.depositResult(player, 1_000L, "expired", "expired-key");
    wallets.depositResult(player, 2_000L, "fresh", "fresh-key");

    long retentionSeconds =
        Math.max(0L, config.modules().scheduler().jobs().cleanup().idempotencySweep().retentionDays())
            * 86_400L;

    byte[][] keyHashes = new byte[2][];
    try (Connection c = connection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT key_hash, created_at_s, expires_at_s FROM core_requests WHERE scope=? ORDER BY created_at_s")) {
      ps.setString(1, "core:deposit");
      try (ResultSet rs = ps.executeQuery()) {
        int idx = 0;
        while (rs.next()) {
          keyHashes[idx] = rs.getBytes(1);
          long created = rs.getLong(2);
          long expires = rs.getLong(3);
          assertEquals(retentionSeconds, expires - created, "ttl should match configured retention");
          idx++;
        }
        assertEquals(2, idx, "expected two idempotency rows");
      }
    }

    long now = Instant.now().getEpochSecond();
    long oldCreated = Math.max(0L, now - retentionSeconds - 3_600L);
    long oldExpires = Math.max(0L, now - 30L);
    long freshCreated = Math.max(0L, now - 60L);
    long freshExpires = now + Math.max(3_600L, retentionSeconds);

    try (Connection c = connection();
        PreparedStatement update =
            c.prepareStatement(
                "UPDATE core_requests SET created_at_s=?, expires_at_s=? WHERE scope=? AND key_hash=?")) {
      update.setLong(1, oldCreated);
      update.setLong(2, oldExpires);
      update.setString(3, "core:deposit");
      update.setBytes(4, keyHashes[0]);
      update.executeUpdate();

      update.setLong(1, freshCreated);
      update.setLong(2, freshExpires);
      update.setString(3, "core:deposit");
      update.setBytes(4, keyHashes[1]);
      update.executeUpdate();
    }

    SchedulerEngine engine = new SchedulerEngine();
    Field servicesField = SchedulerEngine.class.getDeclaredField("services");
    servicesField.setAccessible(true);
    servicesField.set(engine, services);
    Method sweep =
        SchedulerEngine.class.getDeclaredMethod(
            "runIdempotencySweep", Config.IdempotencySweep.class);
    sweep.setAccessible(true);
    sweep.invoke(engine, config.modules().scheduler().jobs().cleanup().idempotencySweep());

    try (Connection c = connection();
        PreparedStatement ps =
            c.prepareStatement("SELECT key_hash FROM core_requests WHERE scope=? ORDER BY created_at_s")) {
      ps.setString(1, "core:deposit");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "fresh row should remain");
        byte[] remaining = rs.getBytes(1);
        assertEquals(0, java.util.Arrays.compare(remaining, keyHashes[1]));
        assertTrue(!rs.next(), "only one row should remain");
      }
    }
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection(
        MariaDbTestSupport.jdbcUrl(DB_NAME),
        MariaDbTestSupport.USER,
        MariaDbTestSupport.PASSWORD);
  }

  private void registerPlayer(UUID uuid, String name) {
    services.players().upsertSeen(uuid, name, Instant.now().getEpochSecond());
  }

  private void wipeTables() throws SQLException {
    try (Connection c = connection(); Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM core_ledger");
      st.executeUpdate("DELETE FROM player_attributes");
      st.executeUpdate("DELETE FROM player_event_seq");
      st.executeUpdate("DELETE FROM core_requests");
      st.executeUpdate("DELETE FROM players");
    }
  }
}
