/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.Attributes;
import dev.holarki.api.Playtime;
import dev.holarki.api.Players;
import dev.holarki.api.Wallets;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import dev.holarki.util.Uuids;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupImporterTest {

  @Test
  void checksumMismatchFailsImport(@TempDir Path tempDir) throws Exception {
    Path snapshot = tempDir.resolve("holarki-test.jsonl");
    int schema = Migrations.currentVersion();
    String header =
        "{" +
            "\"version\":\"jsonl/v1\"," +
            "\"generatedAt\":\"" + Instant.EPOCH + "\"," +
            "\"defaultZone\":\"UTC\"," +
            "\"schemaVersion\":" + schema +
            "}\n";
    Files.writeString(snapshot, header, StandardCharsets.UTF_8);
    Files.writeString(snapshot.resolveSibling(snapshot.getFileName().toString() + ".sha256"), "0000");

    Services services = new GuardedServices();

    assertThrows(
        IOException.class,
        () ->
            BackupImporter.restore(
                services,
                snapshot,
                BackupImporter.Mode.FRESH,
                BackupImporter.FreshStrategy.ATOMIC,
                false,
                false));
  }

  @Test
  void mergeRestoreLeavesExistingPlayersUntouchedByDefault(@TempDir Path tempDir)
      throws Exception {
    String dbName = "backup_importer_test_" + Long.toUnsignedString(System.nanoTime());
    MariaDbTestSupport.ensureDatabase(dbName);
    String jdbcUrl = MariaDbTestSupport.jdbcUrl(dbName);
    try {
      ModuleDatabase moduleDb = new SimpleModuleDatabase(jdbcUrl);
      Services services = new DatabaseServices(moduleDb);

      Migrations.apply(services);

      UUID liveId = UUID.randomUUID();
      byte[] liveBytes = Uuids.toBytes(liveId);
      long liveCreated = Instant.now().getEpochSecond();

      try (Connection c =
              DriverManager.getConnection(jdbcUrl, MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
          PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO players(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s) "
                      + "VALUES(?,?,?,?,?,?)")) {
        ps.setBytes(1, liveBytes);
        ps.setString(2, "LivePlayer");
        ps.setLong(3, 500L);
        ps.setLong(4, liveCreated);
        ps.setLong(5, liveCreated);
        ps.setNull(6, Types.BIGINT);
        ps.executeUpdate();
      }

      try (Connection c =
              DriverManager.getConnection(jdbcUrl, MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
          PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO player_attributes(owner_uuid,attr_key,value_json,created_at_s,updated_at_s) "
                      + "VALUES(?,?,?,?,?)")) {
        ps.setBytes(1, liveBytes);
        ps.setString(2, "prefs.color");
        ps.setString(3, "{\"color\":\"live\"}");
        ps.setLong(4, liveCreated);
        ps.setLong(5, liveCreated);
        ps.executeUpdate();
      }

      UUID newId = UUID.randomUUID();
      byte[] newBytes = Uuids.toBytes(newId);
      long snapshotCreated = liveCreated - 10;

      String header =
          String.format(
              "{\"version\":\"jsonl/v1\",\"generatedAt\":\"%s\",\"defaultZone\":\"UTC\",\"schemaVersion\":%d}\n",
              Instant.EPOCH,
              Migrations.currentVersion());
      String livePlayerLine =
          String.format(
              "{\"table\":\"players\",\"uuid\":\"%s\",\"name\":\"SnapshotLive\",\"balance\":%d,\"createdAt\":%d,\"updatedAt\":%d,\"seenAt\":null}\n",
              liveId,
              999,
              snapshotCreated,
              snapshotCreated);
      String newPlayerLine =
          String.format(
              "{\"table\":\"players\",\"uuid\":\"%s\",\"name\":\"NewPlayer\",\"balance\":%d,\"createdAt\":%d,\"updatedAt\":%d,\"seenAt\":null}\n",
              newId,
              250,
              snapshotCreated,
              snapshotCreated);

      String liveAttrLine =
          String.format(
              "{\"table\":\"player_attributes\",\"owner\":\"%s\",\"key\":\"prefs.color\",\"value\":{\"color\":\"snapshot\"},\"createdAt\":%d,\"updatedAt\":%d}\n",
              liveId,
              snapshotCreated,
              snapshotCreated);
      String newAttrLine =
          String.format(
              "{\"table\":\"player_attributes\",\"owner\":\"%s\",\"key\":\"prefs.color\",\"value\":{\"color\":\"new\"},\"createdAt\":%d,\"updatedAt\":%d}\n",
              newId,
              snapshotCreated,
              snapshotCreated);

      Path snapshot = tempDir.resolve("merge.jsonl");
      Files.writeString(
          snapshot,
          header + livePlayerLine + newPlayerLine + liveAttrLine + newAttrLine,
          StandardCharsets.UTF_8);

      BackupImporter.restore(services, snapshot, BackupImporter.Mode.MERGE, null, false, false);

      try (Connection c =
              DriverManager.getConnection(jdbcUrl, MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD)) {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT name,balance_units FROM players WHERE uuid=?")) {
          ps.setBytes(1, liveBytes);
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("LivePlayer", rs.getString("name"));
            assertEquals(500L, rs.getLong("balance_units"));
          }
        }

        try (PreparedStatement ps =
            c.prepareStatement("SELECT name,balance_units FROM players WHERE uuid=?")) {
          ps.setBytes(1, newBytes);
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("NewPlayer", rs.getString("name"));
            assertEquals(250L, rs.getLong("balance_units"));
          }
        }

        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT value_json,created_at_s,updated_at_s FROM player_attributes WHERE owner_uuid=? AND attr_key=?")) {
          ps.setBytes(1, liveBytes);
          ps.setString(2, "prefs.color");
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("{\"color\":\"live\"}", rs.getString("value_json"));
            assertEquals(liveCreated, rs.getLong("created_at_s"));
            assertEquals(liveCreated, rs.getLong("updated_at_s"));
          }
        }

        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT value_json,created_at_s,updated_at_s FROM player_attributes WHERE owner_uuid=? AND attr_key=?")) {
          ps.setBytes(1, newBytes);
          ps.setString(2, "prefs.color");
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("{\"color\":\"new\"}", rs.getString("value_json"));
            assertEquals(snapshotCreated, rs.getLong("created_at_s"));
            assertEquals(snapshotCreated, rs.getLong("updated_at_s"));
          }
        }
      }
    } finally {
      MariaDbTestSupport.dropDatabase(dbName);
    }
  }

  private static final class GuardedServices implements Services {
    private final ModuleDatabase database = new GuardedDatabase();

    @Override
    public Players players() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Wallets wallets() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Attributes attributes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CoreEvents events() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ModuleDatabase database() {
      return database;
    }

    @Override
    public ScheduledExecutorService scheduler() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Metrics metrics() {
      return null;
    }

    @Override
    public Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}
  }

  private static final class GuardedDatabase implements ModuleDatabase {
    @Override
    public Connection borrowConnection() throws SQLException {
      throw new AssertionError("should not borrow connection when checksum mismatches");
    }

    @Override
    public boolean tryAdvisoryLock(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void releaseAdvisoryLock(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SchemaHelper schema() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class DatabaseServices implements Services {
    private final ModuleDatabase database;

    private DatabaseServices(ModuleDatabase database) {
      this.database = database;
    }

    @Override
    public Players players() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Wallets wallets() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Attributes attributes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CoreEvents events() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ModuleDatabase database() {
      return database;
    }

    @Override
    public ScheduledExecutorService scheduler() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}
  }

  private static final class SimpleModuleDatabase implements ModuleDatabase {
    private final String jdbcUrl;

    private SimpleModuleDatabase(String jdbcUrl) {
      this.jdbcUrl = jdbcUrl;
    }

    @Override
    public Connection borrowConnection() throws SQLException {
      return DriverManager.getConnection(jdbcUrl, MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
    }

    @Override
    public boolean tryAdvisoryLock(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void releaseAdvisoryLock(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
      return action.get();
    }

    @Override
    public SchemaHelper schema() {
      throw new UnsupportedOperationException();
    }
  }
}
