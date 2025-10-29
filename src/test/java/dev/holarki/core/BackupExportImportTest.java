package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import dev.holarki.api.Players;
import dev.holarki.api.Playtime;
import dev.holarki.api.Wallets;
import dev.holarki.api.Attributes;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import dev.holarki.util.Uuids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.mariadb.jdbc.MariaDbDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupExportImportTest {

  @Test
  void emptyStringsSurviveExportImport(@TempDir Path tempDir) throws Exception {
    DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
    builder.setPort(0);
    builder.setBaseDir(tempDir.resolve("db-base").toString());
    builder.setDataDir(tempDir.resolve("db-data").toString());
    builder.setDeletingTemporaryBaseAndDataDirsOnShutdown(true);
    builder.setSecurityDisabled(true);
    builder.addArg("--user=" + MariaDbTestSupport.USER);

    DB db = DB.newEmbeddedDB(builder.build());
    db.start();
    try {
      String dbName = "holarki";
      try (Connection admin =
              DriverManager.getConnection(
                  "jdbc:mariadb://127.0.0.1:" + db.getConfiguration().getPort() + "/mysql",
                  MariaDbTestSupport.USER,
                  MariaDbTestSupport.PASSWORD);
          Statement st = admin.createStatement()) {
        st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName);
      }
      MariaDbDataSource dataSource = new MariaDbDataSource();
      dataSource.setUrl("jdbc:mariadb://127.0.0.1:" + db.getConfiguration().getPort() + "/" + dbName);
      dataSource.setUser(MariaDbTestSupport.USER);
      dataSource.setPassword(MariaDbTestSupport.PASSWORD);
      ModuleDatabase moduleDb = new SimpleModuleDatabase(dataSource);
      Services services = new SimpleServices(moduleDb);

      Migrations.apply(services);

      UUID playerId = UUID.randomUUID();
      byte[] playerBytes = Uuids.toBytes(playerId);
      long now = Instant.now().getEpochSecond();

      try (Connection c = dataSource.getConnection()) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO players(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s)"
                    + " VALUES(?,?,?,?,?,?)")) {
          ps.setBytes(1, playerBytes);
          ps.setString(2, "Alice");
          ps.setLong(3, 0L);
          ps.setLong(4, now);
          ps.setLong(5, now);
          ps.setNull(6, Types.BIGINT);
          ps.executeUpdate();
        }

        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO player_attributes(owner_uuid,attr_key,value_json,created_at_s,updated_at_s)"
                    + " VALUES(?,?,?,?,?)")) {
          ps.setBytes(1, playerBytes);
          ps.setString(2, "note");
          ps.setString(3, "\"\"");
          ps.setLong(4, now);
          ps.setLong(5, now);
          ps.executeUpdate();
        }

        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO core_ledger(ts_s,module_id,op,from_uuid,to_uuid,amount,reason,ok,code,seq,"
                    + "idem_scope,idem_key_hash,old_units,new_units,server_node,extra_json)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
          ps.setLong(1, now);
          ps.setString(2, "ledger");
          ps.setString(3, "CREDIT");
          ps.setNull(4, Types.BINARY);
          ps.setBytes(5, playerBytes);
          ps.setLong(6, 5L);
          ps.setString(7, "");
          ps.setBoolean(8, true);
          ps.setString(9, "");
          ps.setLong(10, 42L);
          ps.setString(11, "");
          ps.setNull(12, Types.BINARY);
          ps.setNull(13, Types.BIGINT);
          ps.setLong(14, 123L);
          ps.setString(15, "");
          ps.setNull(16, Types.LONGVARCHAR);
          ps.executeUpdate();
        }
      }

      Path configPath = tempDir.resolve("config/holarki.json5");
      Files.createDirectories(configPath.getParent());
      Config config = Config.loadOrWriteDefault(configPath);
      Path exportDir = Files.createDirectories(tempDir.resolve("export"));

      BackupExporter.Result exported = BackupExporter.exportAll(services, config, exportDir, false);

      try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
        st.executeUpdate("DELETE FROM core_ledger");
        st.executeUpdate("DELETE FROM player_attributes");
        st.executeUpdate("DELETE FROM player_event_seq");
        st.executeUpdate("DELETE FROM players");
      }

      BackupImporter.restore(
          services,
          exported.file(),
          BackupImporter.Mode.FRESH,
          BackupImporter.FreshStrategy.ATOMIC,
          false,
          false);

      try (Connection c = dataSource.getConnection()) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT value_json FROM player_attributes WHERE owner_uuid=? AND attr_key=?")) {
          ps.setBytes(1, playerBytes);
          ps.setString(2, "note");
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("\"\"", rs.getString(1));
          }
        }

        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT code, server_node, idem_scope FROM core_ledger WHERE seq=?")) {
          ps.setLong(1, 42L);
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("", rs.getString("code"));
            assertEquals("", rs.getString("server_node"));
            assertEquals("", rs.getString("idem_scope"));
          }
        }
      }
    } finally {
      db.stop();
    }
  }

  private static final class SimpleModuleDatabase implements ModuleDatabase {
    private final DataSource dataSource;

    private SimpleModuleDatabase(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public Connection borrowConnection() throws java.sql.SQLException {
      return dataSource.getConnection();
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
    public <T> T withRetry(SQLSupplier<T> action) throws java.sql.SQLException {
      return action.get();
    }

    @Override
    public SchemaHelper schema() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class SimpleServices implements Services {
    private final ModuleDatabase database;

    private SimpleServices(ModuleDatabase database) {
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
    public java.util.concurrent.ScheduledExecutorService scheduler() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
      // No-op for tests.
    }
  }
}
