/* Holarki © 2025 — MIT */
package dev.holarki.modules.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import dev.holarki.core.Config;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void jsonMirrorRemainsWellFormedUnderConcurrency(@TempDir Path tempDir) throws Exception {
    ModuleDatabase database = newNoopDatabase();

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
    Path mirror = tempDir.resolve("ledger.jsonl");
    LedgerService service =
        LedgerService.install(
            database,
            events,
            scheduler,
            null,
            new Config.Ledger(true, 0, new Config.JsonlMirror(true, mirror.toString())));

    int threads = 8;
    int perThread = 50;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      final int threadIndex = i;
      executor.submit(
          () -> {
            ready.countDown();
            try {
              start.await();
              for (int j = 0; j < perThread; j++) {
                service.log(
                    "test",
                    "op",
                    UUID.randomUUID(),
                    null,
                    threadIndex * 1000L + j,
                    "concurrency",
                    true,
                    null,
                    null,
                    null,
                    null);
              }
            } catch (Throwable t) {
              failure.compareAndSet(null, t);
            } finally {
              done.countDown();
            }
          });
    }

    try {
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(done.await(15, TimeUnit.SECONDS));
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      if (failure.get() != null) {
        throw new AssertionError("concurrent writer failed", failure.get());
      }

      assertTrue(Files.exists(mirror));
      var lines = Files.readAllLines(mirror);
      assertEquals(threads * perThread, lines.size());
      for (String line : lines) {
        assertFalse(line.isBlank(), "mirror should not contain blank lines");
        assertDoesNotThrow(() -> JsonParser.parseString(line).getAsJsonObject());
      }
    } finally {
      executor.shutdownNow();
      assertDoesNotThrow(service::close);
      scheduler.shutdownNow();
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
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

  private static ModuleDatabase newNoopDatabase() {
    return new ModuleDatabase() {
      private final SchemaHelper schema = new NoopSchemaHelper();

      @Override
      public Connection borrowConnection() {
        return newNoopConnection();
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
        return schema;
      }
    };
  }

  private static Connection newNoopConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            LedgerServiceTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if ("prepareStatement".equals(name)) {
                return newNoopPreparedStatement();
              }
              if ("close".equals(name)) {
                return null;
              }
              if ("isClosed".equals(name)) {
                return false;
              }
              if ("unwrap".equals(name)) {
                throw new SQLException("unwrap not supported");
              }
              if ("isWrapperFor".equals(name)) {
                return false;
              }
              if ("toString".equals(name)) {
                return "NoopConnection";
              }
              if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
              }
              if ("equals".equals(name)) {
                return proxy == args[0];
              }
              throw new UnsupportedOperationException("Connection." + name);
            });
  }

  private static PreparedStatement newNoopPreparedStatement() {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            LedgerServiceTest.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              String name = method.getName();
              switch (name) {
                case "setLong":
                case "setString":
                case "setNull":
                case "setBytes":
                case "setBoolean":
                case "close":
                case "clearParameters":
                  return null;
                case "executeUpdate":
                  return 1;
                case "toString":
                  return "NoopPreparedStatement";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                case "unwrap":
                  throw new SQLException("unwrap not supported");
                case "isWrapperFor":
                  return false;
                default:
                  throw new UnsupportedOperationException("PreparedStatement." + name);
              }
            });
  }

  private static final class NoopSchemaHelper implements SchemaHelper {
    @Override
    public void ensureTable(String createSql) {}

    @Override
    public void ensureTable(String table, String createSql) {}

    @Override
    public boolean tableExists(String table) {
      return true;
    }

    @Override
    public boolean hasColumn(String table, String column) {
      return true;
    }

    @Override
    public void addColumnIfMissing(String table, String column, String columnDef) {}

    @Override
    public boolean hasIndex(String table, String indexName) {
      return true;
    }

    @Override
    public void ensureIndex(String table, String indexName, String createIndexSql) {}

    @Override
    public boolean hasCheck(String table, String checkName) {
      return true;
    }

    @Override
    public void ensureCheck(String table, String checkName, String addCheckSql) {}

    @Override
    public boolean hasPrimaryKey(String table, String constraintName) {
      return true;
    }

    @Override
    public void ensurePrimaryKey(String table, String constraintName, String addPrimaryKeySql) {}

    @Override
    public boolean hasForeignKey(String table, String constraintName) {
      return true;
    }

    @Override
    public void ensureForeignKey(String table, String constraintName, String addForeignKeySql) {}
  }
}
