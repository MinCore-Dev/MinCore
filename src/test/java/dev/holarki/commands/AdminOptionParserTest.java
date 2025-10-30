/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.core.BackupImporter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AdminOptionParserTest {

  @Test
  void tokenizeHonorsQuotes() {
    List<String> tokens =
        AdminOptionParser.tokenize("--out 'dir name' --gzip \"true\" --flag\nnext");
    assertEquals(List.of("--out", "dir name", "--gzip", "true", "--flag", "next"), tokens);
  }

  @Test
  void tokenizeRejectsUnterminatedQuotes() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> AdminOptionParser.tokenize("'oops"));
    assertEquals("unterminated quote in arguments", ex.getMessage());
  }

  @Test
  void exportOptionsParseValues() {
    ExportOptions options = ExportOptions.parse("--out '/tmp/export' --gzip false");
    assertEquals(Path.of("/tmp/export"), options.outDir);
    assertEquals(Boolean.FALSE, options.gzipOverride);
  }

  @Test
  void exportOptionsRejectInvalidBoolean() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> ExportOptions.parse("--gzip maybe"));
    assertEquals("--gzip must be true or false", ex.getMessage());
  }

  @Test
  void restoreOptionsMergeClearsStrategy() {
    RestoreOptions options = RestoreOptions.parse("--mode merge --atomic --overwrite");
    assertEquals(BackupImporter.Mode.MERGE, options.mode);
    assertNull(options.strategy);
    assertTrue(options.overwrite);
    assertFalse(options.skipFkChecks);
    assertFalse(options.allowMissingChecksum);
  }

  @Test
  void restoreOptionsParsesAliasAndPath() {
    RestoreOptions options =
        RestoreOptions.parse("--mode fresh --from 'dir with space' --skip-fk --staging");
    assertEquals(BackupImporter.Mode.FRESH, options.mode);
    assertEquals(BackupImporter.FreshStrategy.STAGING, options.strategy);
    assertEquals(Path.of("dir with space"), options.from);
    assertTrue(options.skipFkChecks);
    assertFalse(options.allowMissingChecksum);
  }

  @Test
  void restoreOptionsAllowMissingChecksumFlag() {
    RestoreOptions options =
        RestoreOptions.parse("--mode fresh --allow-missing-checksum --atomic");
    assertTrue(options.allowMissingChecksum);
    assertEquals(BackupImporter.Mode.FRESH, options.mode);
  }

  @Test
  void doctorOptionsDefaultWhenEmpty() {
    DoctorOptions options = DoctorOptions.parse("");
    assertTrue(options.fk);
    assertTrue(options.counts);
    assertFalse(options.orphans);
    assertFalse(options.analyze);
    assertFalse(options.locks);
  }

  @Test
  void doctorOptionsHonorsExplicitFlags() {
    DoctorOptions options = DoctorOptions.parse("--locks --orphans");
    assertTrue(options.hasAny());
    assertTrue(options.orphans);
    assertTrue(options.locks);
    assertFalse(options.fk);
    assertFalse(options.counts);
  }

  @Test
  void unknownFlagFailsFast() {
    assertThrows(IllegalArgumentException.class, () -> DoctorOptions.parse("--nope"));
  }

  @Test
  void restoreModeRequired() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> RestoreOptions.parse("--from dir"));
    assertEquals("--mode required", ex.getMessage());
  }

  @Test
  void restoreModeValidatesValues() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> RestoreOptions.parse("--mode other"));
    assertEquals("unknown mode: other", ex.getMessage());
  }
}
