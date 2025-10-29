/* Holarki © 2025 — MIT */
package dev.holarki.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test utilities for filesystem operations. */
final class TestFiles {
  private TestFiles() {}

  static void deleteRecursively(Path path) {
    if (path == null || Files.notExists(path)) {
      return;
    }
    try {
      Files.walk(path)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }
}
