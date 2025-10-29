/* Holarki © 2025 — MIT */
package dev.holarki.modules.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import dev.holarki.core.Config;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerServiceTest {

  private static final Method ENSURE_PARENT_METHOD;

  static {
    try {
      ENSURE_PARENT_METHOD = LedgerService.class.getDeclaredMethod("ensureParent", Path.class);
      ENSURE_PARENT_METHOD.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Test
  void ensureParentToleratesFlatMirrorPath(@TempDir Path tempDir) {
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toString());
    try {
      assertDoesNotThrow(() -> invokeEnsureParent(Path.of("ledger.jsonl")));
      try (var stream = Files.list(tempDir)) {
        assertEquals(0L, stream.count(), "flat mirror path should not create directories");
      } catch (IOException ioe) {
        throw new AssertionError("failed to inspect temp directory", ioe);
      }
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }
  }

  @Test
  void ensureParentCreatesMissingDirectories(@TempDir Path tempDir) throws Exception {
    Path mirrorFile = tempDir.resolve("mirror/sub/ledger.jsonl");
    Path parent = mirrorFile.getParent();
    assertFalse(Files.exists(parent));

    invokeEnsureParent(mirrorFile);

    assertTrue(Files.isDirectory(parent));
  }

  @Test
  void installPropagatesEnsureTableFailure() {
    SQLException failure = new SQLException("boom");
    ModuleDatabase database = new ModuleDatabase() {
      private final SchemaHelper helper = new FailingSchemaHelper(failure);

      @Override
      public Connection borrowConnection() {
        throw new UnsupportedOperationException("not needed for test");
      }

      @Override
      public boolean tryAdvisoryLock(String name) {
        return false;
      }

      @Override
      public void releaseAdvisoryLock(String name) {}

      @Override
      public <T> T withRetry(SQLSupplier<T> action) throws SQLException {
        return action.get();
      }

      @Override
      public SchemaHelper schema() {
        return helper;
      }
    };

    CoreEvents events = new CoreEvents() {
      @Override
      public AutoCloseable onBalanceChanged(Consumer<BalanceChangedEvent> h) {
        return () -> {};
      }

      @Override
      public AutoCloseable onPlayerRegistered(Consumer<PlayerRegisteredEvent> h) {
        return () -> {};
      }

      @Override
      public AutoCloseable onPlayerSeenUpdated(Consumer<PlayerSeenUpdatedEvent> h) {
        return () -> {};
      }
    };

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () ->
                  LedgerService.install(
                      database,
                      events,
                      scheduler,
                      null,
                      new Config.Ledger(true, 0, new Config.JsonlMirror(false, null))));
      assertEquals(failure, thrown.getCause());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static final class FailingSchemaHelper implements SchemaHelper {
    private final SQLException failure;

    private FailingSchemaHelper(SQLException failure) {
      this.failure = failure;
    }

    @Override
    public void ensureTable(String createSql) throws SQLException {
      throw failure;
    }

    @Override
    public void ensureTable(String table, String createSql) throws SQLException {
      throw failure;
    }

    @Override
    public boolean tableExists(String table) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean hasColumn(String table, String column) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void addColumnIfMissing(String table, String column, String columnDef)
        throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean hasIndex(String table, String indexName) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void ensureIndex(String table, String indexName, String createIndexSql)
        throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean hasCheck(String table, String checkName) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void ensureCheck(String table, String checkName, String addCheckSql)
        throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean hasPrimaryKey(String table, String constraintName) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void ensurePrimaryKey(String table, String constraintName, String addPrimaryKeySql)
        throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean hasForeignKey(String table, String constraintName) throws SQLException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void ensureForeignKey(String table, String constraintName, String addForeignKeySql)
        throws SQLException {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static void invokeEnsureParent(Path path) throws Exception {
    try {
      ENSURE_PARENT_METHOD.invoke(null, path);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception ex) {
        throw ex;
      }
      if (cause instanceof Error err) {
        throw err;
      }
      throw new RuntimeException(cause);
    }
  }
}
