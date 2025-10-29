/* MinCore © 2025 — MIT */
package dev.mincore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mincore.api.Attributes;
import dev.mincore.api.Players;
import dev.mincore.api.Playtime;
import dev.mincore.api.Wallets;
import dev.mincore.api.events.CoreEvents;
import dev.mincore.api.storage.ModuleDatabase;
import dev.mincore.api.storage.SchemaHelper;
import dev.mincore.util.Uuids;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Integration test covering the backup/export and restore lifecycle. */
final class BackupCycleTest {
  private static final String DB_NAME = "mincore_test";

  @BeforeAll
  static void prepareDatabase() throws Exception {
    MariaDbTestSupport.ensureDatabase(DB_NAME);
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    MariaDbTestSupport.dropDatabase(DB_NAME);
  }

  @Test
  void backupAndRestoreCyclePreservesData() throws Exception {
    Path backupDir = Files.createTempDirectory("mincore-backup-test");
    TestServices services =
        new TestServices(jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
    Config config = TestConfigFactory.create(DB_NAME, backupDir);

    try {
      Migrations.apply(services);
      seedTestData();

      Counts baselineCounts = readCounts();
      List<PlayerRow> playersBefore = readPlayers();
      List<AttributeRow> attributesBefore = readAttributes();
      List<EventSeqRow> eventSeqBefore = readEventSequences();
      List<LedgerRow> ledgerBefore = readLedger();

      BackupExporter.Result exportResult =
          BackupExporter.exportAll(services, config, backupDir, Boolean.FALSE);

      assertNotNull(exportResult);
      assertEquals(baselineCounts.players(), exportResult.players());
      assertEquals(baselineCounts.attributes(), exportResult.attributes());
      assertEquals(baselineCounts.eventSeq(), exportResult.eventSeq());
      assertEquals(baselineCounts.ledger(), exportResult.ledger());

      Path checksum =
          Objects.requireNonNull(exportResult.file().getParent())
              .resolve(exportResult.file().getFileName().toString() + ".sha256");
      assertTrue(Files.exists(checksum));
      String hash = Files.readString(checksum).trim();
      assertEquals(64, hash.length(), "checksum should be 64 hex characters");

      wipeAndCorrupt();

      BackupImporter.Result restoreResult =
          BackupImporter.restore(
              services,
              exportResult.file(),
              BackupImporter.Mode.FRESH,
              BackupImporter.FreshStrategy.ATOMIC,
              false,
              false);

      assertNotNull(restoreResult);
      assertEquals(exportResult.players(), restoreResult.players());
      assertEquals(exportResult.attributes(), restoreResult.attributes());
      assertEquals(exportResult.eventSeq(), restoreResult.eventSeq());
      assertEquals(exportResult.ledger(), restoreResult.ledger());

      Counts afterCounts = readCounts();
      assertEquals(baselineCounts, afterCounts);
      assertEquals(playersBefore, readPlayers());
      assertEquals(attributesBefore, readAttributes());
      assertEquals(eventSeqBefore, readEventSequences());
      assertEquals(ledgerBefore, readLedger());
      assertEquals(Migrations.currentVersion(), readSchemaVersion());
    } finally {
      services.shutdown();
      deleteRecursively(backupDir);
    }
  }

  @Test
  void restoreAllowsLedgerEntryWithoutLegacyFields() throws Exception {
    Path snapshot = Files.createTempFile("mincore-ledger-compat", ".jsonl");
    TestServices services =
        new TestServices(jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);

    try {
      Migrations.apply(services);
      truncateAllTables();

      try (BufferedWriter writer = Files.newBufferedWriter(snapshot, StandardCharsets.UTF_8)) {
        String header =
            """
                {"version":"jsonl/v1","generatedAt":"%s","defaultZone":"UTC","schemaVersion":%d}"""
                .formatted(Instant.now().toString(), Migrations.currentVersion())
                .strip();
        String ledger =
            """
                {"table":"core_ledger","ts":1234,"module":"legacy-module","op":"deposit","from":"","to":"00000000-0000-0000-0000-000000000001","amount":100,"reason":"legacy","ok":true,"code":"","seq":1,"idemScope":"","idemKey":"","oldUnits":null,"newUnits":null,"serverNode":"","extra":null}"""
                .strip();

        writer.write(header);
        writer.newLine();
        writer.write(ledger);
        writer.newLine();
      }

      BackupImporter.Result result =
          BackupImporter.restore(
              services,
              snapshot,
              BackupImporter.Mode.FRESH,
              BackupImporter.FreshStrategy.ATOMIC,
              false,
              false);

      assertEquals(0L, result.players());
      assertEquals(0L, result.attributes());
      assertEquals(0L, result.eventSeq());
      assertEquals(1L, result.ledger());

      List<LedgerRow> ledgerRows = readLedger();
      assertEquals(1, ledgerRows.size());
      LedgerRow row = ledgerRows.get(0);
      assertEquals("legacy-module", row.module());
      assertEquals(1234L, row.ts());
      assertEquals(100L, row.amount());
    } finally {
      services.shutdown();
      Files.deleteIfExists(snapshot);
    }
  }

  private static String jdbcUrl() {
    return MariaDbTestSupport.jdbcUrl(DB_NAME);
  }

  private static void seedTestData() throws SQLException {
    try (Connection c =
        DriverManager.getConnection(
            jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD)) {
      c.setAutoCommit(false);

      try (Statement st = c.createStatement()) {
        st.executeUpdate("DELETE FROM core_ledger");
        st.executeUpdate("DELETE FROM player_attributes");
        st.executeUpdate("DELETE FROM player_event_seq");
        st.executeUpdate("DELETE FROM players");
        st.executeUpdate("DELETE FROM core_requests");
      }

      UUID alpha = UUID.fromString("00000000-0000-0000-0000-000000000001");
      UUID bravo = UUID.fromString("00000000-0000-0000-0000-000000000002");

      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO players("
                  + "uuid, name, balance_units, created_at_s, updated_at_s, seen_at_s) "
                  + "VALUES(?, ?, ?, ?, ?, ?)"); ) {
        ps.setBytes(1, Uuids.toBytes(alpha));
        ps.setString(2, "Alpha");
        ps.setLong(3, 2_500L);
        ps.setLong(4, 1_000L);
        ps.setLong(5, 1_100L);
        ps.setLong(6, 1_200L);
        ps.executeUpdate();

        ps.setBytes(1, Uuids.toBytes(bravo));
        ps.setString(2, "Bravo");
        ps.setLong(3, 4_000L);
        ps.setLong(4, 2_000L);
        ps.setLong(5, 2_050L);
        ps.setNull(6, java.sql.Types.BIGINT);
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          c.prepareStatement("INSERT INTO player_event_seq(uuid, seq) VALUES(?, ?)"); ) {
        ps.setBytes(1, Uuids.toBytes(alpha));
        ps.setLong(2, 42L);
        ps.executeUpdate();

        ps.setBytes(1, Uuids.toBytes(bravo));
        ps.setLong(2, 84L);
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO player_attributes("
                  + "owner_uuid, attr_key, value_json, created_at_s, updated_at_s) "
                  + "VALUES(?, ?, ?, ?, ?)"); ) {
        ps.setBytes(1, Uuids.toBytes(alpha));
        ps.setString(2, "title");
        ps.setString(3, "{\"rank\":\"captain\"}");
        ps.setLong(4, 1_010L);
        ps.setLong(5, 1_110L);
        ps.executeUpdate();

        ps.setBytes(1, Uuids.toBytes(bravo));
        ps.setString(2, "title");
        ps.setString(3, "{\"rank\":\"scout\"}");
        ps.setLong(4, 2_010L);
        ps.setLong(5, 2_110L);
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO core_ledger("
                  + "ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, "
                  + "idem_scope, idem_key_hash, old_units, new_units, server_node, extra_json) "
                  + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"); ) {
        ps.setLong(1, 3_000L);
        ps.setString(2, "test-module");
        ps.setString(3, "deposit");
        ps.setBytes(4, null);
        ps.setBytes(5, Uuids.toBytes(alpha));
        ps.setLong(6, 500L);
        ps.setString(7, "initial");
        ps.setBoolean(8, true);
        ps.setString(9, "OK");
        ps.setLong(10, 1L);
        ps.setString(11, "wallet:test");
        ps.setNull(12, java.sql.Types.BINARY);
        ps.setLong(13, 0L);
        ps.setLong(14, 2_500L);
        ps.setString(15, "node-a");
        ps.setString(16, "{\"note\":\"seed\"}");
        ps.executeUpdate();

        ps.setLong(1, 3_600L);
        ps.setString(2, "test-module");
        ps.setString(3, "transfer");
        ps.setBytes(4, Uuids.toBytes(alpha));
        ps.setBytes(5, Uuids.toBytes(bravo));
        ps.setLong(6, 250L);
        ps.setString(7, "gift");
        ps.setBoolean(8, true);
        ps.setString(9, "OK");
        ps.setLong(10, 2L);
        ps.setString(11, "wallet:test");
        ps.setNull(12, java.sql.Types.BINARY);
        ps.setLong(13, 2_500L);
        ps.setLong(14, 2_250L);
        ps.setString(15, "node-a");
        ps.setString(16, "{\"note\":\"gift\"}");
        ps.executeUpdate();
      }

      c.commit();
    }
  }

  private static void wipeAndCorrupt() throws SQLException {
    try (Connection c =
        DriverManager.getConnection(
            jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD)) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.executeUpdate("DELETE FROM core_ledger");
        st.executeUpdate("DELETE FROM player_attributes");
        st.executeUpdate("DELETE FROM player_event_seq");
        st.executeUpdate("DELETE FROM players");
        st.executeUpdate("DELETE FROM core_requests");
      }

      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO players("
                  + "uuid, name, balance_units, created_at_s, updated_at_s, seen_at_s) "
                  + "VALUES(?, ?, ?, ?, ?, ?)"); ) {
        ps.setBytes(1, Uuids.toBytes(UUID.fromString("99999999-9999-9999-9999-999999999999")));
        ps.setString(2, "Corrupted");
        ps.setLong(3, 123L);
        ps.setLong(4, 10L);
        ps.setLong(5, 20L);
        ps.setLong(6, 30L);
        ps.executeUpdate();
      }

      c.commit();
    }
  }

  private static void truncateAllTables() throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM core_ledger");
      st.executeUpdate("DELETE FROM player_attributes");
      st.executeUpdate("DELETE FROM player_event_seq");
      st.executeUpdate("DELETE FROM players");
      st.executeUpdate("DELETE FROM core_requests");
    }
  }

  private static Counts readCounts() throws SQLException {
    final String sql =
        "SELECT (SELECT COUNT(*) FROM players) AS players,"
            + " (SELECT COUNT(*) FROM player_attributes) AS attrs,"
            + " (SELECT COUNT(*) FROM player_event_seq) AS event_seq,"
            + " (SELECT COUNT(*) FROM core_ledger) AS ledger";
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return new Counts(
          rs.getLong("players"),
          rs.getLong("attrs"),
          rs.getLong("event_seq"),
          rs.getLong("ledger"));
    }
  }

  private static List<PlayerRow> readPlayers() throws SQLException {
    final String sql =
        "SELECT uuid, name, balance_units, created_at_s, updated_at_s, seen_at_s "
            + "FROM players ORDER BY name";
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<PlayerRow> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(
            new PlayerRow(
                uuid(rs.getBytes("uuid")),
                rs.getString("name"),
                rs.getLong("balance_units"),
                rs.getLong("created_at_s"),
                rs.getLong("updated_at_s"),
                rs.getObject("seen_at_s") != null ? rs.getLong("seen_at_s") : null));
      }
      return rows;
    }
  }

  private static List<AttributeRow> readAttributes() throws SQLException {
    final String sql =
        "SELECT owner_uuid, attr_key, value_json, created_at_s, updated_at_s "
            + "FROM player_attributes ORDER BY attr_key, owner_uuid";
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<AttributeRow> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(
            new AttributeRow(
                uuid(rs.getBytes("owner_uuid")),
                rs.getString("attr_key"),
                rs.getString("value_json"),
                rs.getLong("created_at_s"),
                rs.getLong("updated_at_s")));
      }
      return rows;
    }
  }

  private static List<EventSeqRow> readEventSequences() throws SQLException {
    final String sql = "SELECT uuid, seq FROM player_event_seq ORDER BY uuid";
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<EventSeqRow> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(new EventSeqRow(uuid(rs.getBytes("uuid")), rs.getLong("seq")));
      }
      return rows;
    }
  }

  private static List<LedgerRow> readLedger() throws SQLException {
    final String sql =
        "SELECT ts_s, module_id, op, from_uuid, to_uuid, amount, reason, ok, code, seq, "
            + "idem_scope, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY ts_s, seq, module_id";
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<LedgerRow> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(
            new LedgerRow(
                rs.getLong("ts_s"),
                rs.getString("module_id"),
                rs.getString("op"),
                uuid(rs.getBytes("from_uuid")),
                uuid(rs.getBytes("to_uuid")),
                rs.getLong("amount"),
                rs.getString("reason"),
                rs.getBoolean("ok"),
                rs.getString("code"),
                rs.getLong("seq"),
                rs.getString("idem_scope"),
                rs.getObject("old_units") != null ? rs.getLong("old_units") : null,
                rs.getObject("new_units") != null ? rs.getLong("new_units") : null,
                rs.getString("server_node"),
                rs.getString("extra_json")));
      }
      return rows;
    }
  }

  private static int readSchemaVersion() throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                jdbcUrl(), MariaDbTestSupport.USER, MariaDbTestSupport.PASSWORD);
        PreparedStatement ps =
            c.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM core_schema_version");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static UUID uuid(byte[] data) {
    if (data == null) {
      return null;
    }
    ByteBuffer buffer = ByteBuffer.wrap(data);
    long high = buffer.getLong();
    long low = buffer.getLong();
    return new UUID(high, low);
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (Files.notExists(path)) {
      return;
    }
    Files.walk(path)
        .sorted((a, b) -> b.compareTo(a))
        .forEach(
            p -> {
              try {
                Files.deleteIfExists(p);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  private record Counts(long players, long attributes, long eventSeq, long ledger) {}

  private record PlayerRow(
      UUID uuid, String name, long balance, long createdAt, long updatedAt, Long seenAt) {}

  private record AttributeRow(
      UUID owner, String key, String value, long createdAt, long updatedAt) {}

  private record EventSeqRow(UUID uuid, long seq) {}

  private record LedgerRow(
      long ts,
      String module,
      String op,
      UUID from,
      UUID to,
      long amount,
      String reason,
      boolean ok,
      String code,
      long seq,
      String scope,
      Long oldUnits,
      Long newUnits,
      String serverNode,
      String extraJson) {}

  /** Minimal {@link Services} implementation for tests backed by a JDBC URL. */
  private static final class TestServices implements Services {
    private final TestModuleDatabase database;
    private final ScheduledExecutorService scheduler;
    private final Playtime playtime;

    TestServices(String jdbcUrl, String user, String password) {
      this.database = new TestModuleDatabase(jdbcUrl, user, password);
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "mincore-test-scheduler");
                t.setDaemon(true);
                return t;
              });
      this.playtime = new PlaytimeImpl();
    }

    @Override
    public Players players() {
      throw new UnsupportedOperationException("players() not used in tests");
    }

    @Override
    public Wallets wallets() {
      throw new UnsupportedOperationException("wallets() not used in tests");
    }

    @Override
    public Attributes attributes() {
      throw new UnsupportedOperationException("attributes() not used in tests");
    }

    @Override
    public CoreEvents events() {
      throw new UnsupportedOperationException("events() not used in tests");
    }

    @Override
    public ModuleDatabase database() {
      return database;
    }

    @Override
    public ScheduledExecutorService scheduler() {
      return scheduler;
    }

    @Override
    public Playtime playtime() {
      return playtime;
    }

    @Override
    public void shutdown() throws IOException {
      scheduler.shutdownNow();
      database.close();
      playtime.close();
    }
  }

  /** ModuleDatabase backed by {@link DriverManager} connections. */
  private static final class TestModuleDatabase implements ModuleDatabase, AutoCloseable {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    TestModuleDatabase(String jdbcUrl, String user, String password) {
      this.jdbcUrl = jdbcUrl;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection borrowConnection() throws SQLException {
      return DriverManager.getConnection(jdbcUrl, user, password);
    }

    @Override
    public boolean tryAdvisoryLock(String name) {
      Objects.requireNonNull(name, "name");
      try (Connection c = borrowConnection();
          PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
        ps.setString(1, name);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() && rs.getInt(1) == 1;
        }
      } catch (SQLException e) {
        return false;
      }
    }

    @Override
    public void releaseAdvisoryLock(String name) {
      try (Connection c = borrowConnection();
          PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        ps.setString(1, name);
        ps.executeQuery();
      } catch (SQLException ignored) {
      }
    }

    @Override
    public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
      SQLException last = null;
      for (int i = 0; i < 3; i++) {
        try {
          return action.get();
        } catch (SQLException e) {
          last = e;
        }
      }
      throw last;
    }

    @Override
    public SchemaHelper schema() {
      return new SchemaHelper() {
        @Override
        public void ensureTable(String createSql) throws SQLException {
          ensureTable(null, createSql);
        }

        @Override
        public void ensureTable(String table, String createSql) throws SQLException {
          if (table != null && tableExists(table)) {
            return;
          }
          try (Connection c = borrowConnection();
              Statement st = c.createStatement()) {
            st.execute(createSql);
          }
        }

        @Override
        public boolean tableExists(String table) throws SQLException {
          try (Connection c = borrowConnection();
              ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
          }
        }

        @Override
        public boolean hasColumn(String table, String column) throws SQLException {
          try (Connection c = borrowConnection();
              ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
          }
        }

        @Override
        public void addColumnIfMissing(String table, String column, String columnDef)
            throws SQLException {
          if (!hasColumn(table, column)) {
            try (Connection c = borrowConnection();
                Statement st = c.createStatement()) {
              st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
            }
          }
        }

        @Override
        public boolean hasIndex(String table, String indexName) throws SQLException {
          try (Connection c = borrowConnection();
              ResultSet rs = c.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
              if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                return true;
              }
            }
          }
          return false;
        }

        @Override
        public void ensureIndex(String table, String indexName, String createIndexSql)
            throws SQLException {
          if (hasIndex(table, indexName)) {
            return;
          }
          try (Connection c = borrowConnection();
              Statement st = c.createStatement()) {
            st.execute(createIndexSql);
          }
        }

        @Override
        public boolean hasCheck(String table, String checkName) throws SQLException {
          try (Connection c = borrowConnection();
              PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                          + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, checkName);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        }

        @Override
        public void ensureCheck(String table, String checkName, String addCheckSql)
            throws SQLException {
          if (hasCheck(table, checkName)) {
            return;
          }
          try (Connection c = borrowConnection();
              Statement st = c.createStatement()) {
            st.execute(addCheckSql);
          }
        }

        @Override
        public boolean hasPrimaryKey(String table, String constraintName) throws SQLException {
          try (Connection c = borrowConnection();
              ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
              if (constraintName.equalsIgnoreCase(rs.getString("PK_NAME"))) {
                return true;
              }
            }
          }
          return false;
        }

        @Override
        public void ensurePrimaryKey(String table, String constraintName, String addPrimaryKeySql)
            throws SQLException {
          if (hasPrimaryKey(table, constraintName)) {
            return;
          }
          try (Connection c = borrowConnection();
              Statement st = c.createStatement()) {
            st.execute(addPrimaryKeySql);
          }
        }

        @Override
        public boolean hasForeignKey(String table, String constraintName) throws SQLException {
          try (Connection c = borrowConnection();
              PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                          + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                          + "AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE='FOREIGN KEY'")) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        }

        @Override
        public void ensureForeignKey(String table, String constraintName, String addForeignKeySql)
            throws SQLException {
          if (hasForeignKey(table, constraintName)) {
            return;
          }
          try (Connection c = borrowConnection();
              Statement st = c.createStatement()) {
            st.execute(addForeignKeySql);
          }
        }
      };
    }

    @Override
    public void close() {}
  }
}
