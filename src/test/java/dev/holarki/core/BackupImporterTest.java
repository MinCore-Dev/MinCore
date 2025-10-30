/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.holarki.api.Attributes;
import dev.holarki.api.Playtime;
import dev.holarki.api.Players;
import dev.holarki.api.Wallets;
import dev.holarki.api.events.CoreEvents;
import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.api.storage.SchemaHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
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
}
