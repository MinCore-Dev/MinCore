/* Holarki © 2025 — MIT */
package dev.holarki.modules.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
