/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTemplateWriterTest {

  @Test
  void writeExample_allowsPathWithoutParent() throws IOException {
    Path path = Path.of("holarki-template-" + UUID.randomUUID() + ".json5.example");
    try {
      ConfigTemplateWriter.writeExample(path, "parentless");

      assertTrue(Files.exists(path), "file should be created even without a parent directory");
      assertEquals("parentless", Files.readString(path));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void writeExample_createsMissingParent(@TempDir Path tempDir) throws IOException {
    Path path = tempDir.resolve("nested").resolve("holarki.json5.example");

    ConfigTemplateWriter.writeExample(path, "with-parent");

    assertTrue(Files.isDirectory(path.getParent()), "expected parent directory to be created");
    assertEquals("with-parent", Files.readString(path));
  }
}
