/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import dev.holarki.core.BackupImporter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class RestoreOptions {
  final BackupImporter.Mode mode;
  final BackupImporter.FreshStrategy strategy;
  final Path from;
  final boolean overwrite;
  final boolean skipFkChecks;
  final boolean allowMissingChecksum;

  private RestoreOptions(
      BackupImporter.Mode mode,
      BackupImporter.FreshStrategy strategy,
      Path from,
      boolean overwrite,
      boolean skipFkChecks,
      boolean allowMissingChecksum) {
    this.mode = mode;
    this.strategy = strategy;
    this.from = from;
    this.overwrite = overwrite;
    this.skipFkChecks = skipFkChecks;
    this.allowMissingChecksum = allowMissingChecksum;
  }

  static RestoreOptions parse(String raw) {
    BackupImporter.Mode mode = null;
    BackupImporter.FreshStrategy strategy = BackupImporter.FreshStrategy.ATOMIC;
    Path from = null;
    boolean overwrite = false;
    boolean skipFkChecks = false;
    boolean allowMissingChecksum = false;

    AdminOptionParser parser = AdminOptionParser.from(raw);
    List<AdminOptionParser.ParsedOption<RestoreFlag>> parsed =
        parser.collect(RestoreFlag.values());
    for (AdminOptionParser.ParsedOption<RestoreFlag> option : parsed) {
      switch (option.option()) {
        case MODE -> {
          String val = option.value().toLowerCase(Locale.ROOT);
          mode =
              switch (val) {
                case "fresh" -> BackupImporter.Mode.FRESH;
                case "merge" -> BackupImporter.Mode.MERGE;
                default -> throw new IllegalArgumentException("unknown mode: " + val);
              };
        }
        case ATOMIC -> strategy = BackupImporter.FreshStrategy.ATOMIC;
        case STAGING -> strategy = BackupImporter.FreshStrategy.STAGING;
        case FROM -> from = Path.of(option.value());
        case OVERWRITE -> overwrite = true;
        case SKIP_FK_CHECKS -> skipFkChecks = true;
        case ALLOW_MISSING_CHECKSUM -> allowMissingChecksum = true;
      }
    }

    if (mode == null) {
      throw new IllegalArgumentException("--mode required");
    }
    if (mode == BackupImporter.Mode.MERGE) {
      strategy = null;
    }

    return new RestoreOptions(mode, strategy, from, overwrite, skipFkChecks, allowMissingChecksum);
  }

  private enum RestoreFlag implements AdminOptionParser.NamedOption {
    MODE(true, "--mode requires fresh|merge", List.of("--mode")),
    ATOMIC(false, null, List.of("--atomic")),
    STAGING(false, null, List.of("--staging")),
    FROM(true, "--from requires a path", List.of("--from")),
    OVERWRITE(false, null, List.of("--overwrite")),
    SKIP_FK_CHECKS(false, null, List.of("--skip-fk-checks", "--skip-fk")),
    ALLOW_MISSING_CHECKSUM(false, null, List.of("--allow-missing-checksum"));

    private final boolean requiresValue;
    private final String missingValueMessage;
    private final List<String> tokens;

    RestoreFlag(boolean requiresValue, String missingValueMessage, List<String> tokens) {
      this.requiresValue = requiresValue;
      this.missingValueMessage = missingValueMessage;
      this.tokens = tokens;
    }

    @Override
    public List<String> tokens() {
      return tokens;
    }

    @Override
    public boolean requiresValue() {
      return requiresValue;
    }

    @Override
    public String missingValueMessage() {
      return missingValueMessage != null
          ? missingValueMessage
          : AdminOptionParser.NamedOption.super.missingValueMessage();
    }
  }
}
