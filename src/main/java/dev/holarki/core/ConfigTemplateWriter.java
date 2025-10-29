/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Writes/updates the {@code holarki.json5.example} config template. */
final class ConfigTemplateWriter {

  private ConfigTemplateWriter() {}

  /**
   * Writes the example file if missing or if the contents changed.
   *
   * @param path destination path (usually {@code config/holarki.json5.example})
   * @param contents canonical template to persist
   */
  static void writeExample(Path path, String contents) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(contents, "contents");

    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      byte[] data = contents.getBytes(StandardCharsets.UTF_8);
      if (Files.exists(path)) {
        byte[] current = Files.readAllBytes(path);
        if (java.util.Arrays.equals(current, data)) {
          return; // already up-to-date
        }
      }
      Files.write(path, data);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write config template: " + path, e);
    }
  }
}
