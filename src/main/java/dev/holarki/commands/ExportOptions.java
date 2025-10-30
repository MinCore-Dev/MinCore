/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class ExportOptions {
  final Path outDir;
  final Boolean gzipOverride;

  private ExportOptions(Path outDir, Boolean gzipOverride) {
    this.outDir = outDir;
    this.gzipOverride = gzipOverride;
  }

  static ExportOptions parse(String raw) {
    Path out = null;
    Boolean gzip = null;
    AdminOptionParser parser = AdminOptionParser.from(raw);
    List<AdminOptionParser.ParsedOption<ExportFlag>> parsed =
        parser.collect(ExportFlag.values());
    for (AdminOptionParser.ParsedOption<ExportFlag> option : parsed) {
      switch (option.option()) {
        case OUT -> out = Path.of(option.value());
        case GZIP -> {
          String val = option.value().toLowerCase(Locale.ROOT);
          if (!"true".equals(val) && !"false".equals(val)) {
            throw new IllegalArgumentException("--gzip must be true or false");
          }
          gzip = Boolean.valueOf(val);
        }
      }
    }
    return new ExportOptions(out, gzip);
  }

  private enum ExportFlag implements AdminOptionParser.NamedOption {
    OUT(true, "--out requires a path", List.of("--out")),
    GZIP(true, "--gzip requires true|false", List.of("--gzip"));

    private final boolean requiresValue;
    private final String missingValueMessage;
    private final List<String> tokens;

    ExportFlag(boolean requiresValue, String missingValueMessage, List<String> tokens) {
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
      return missingValueMessage;
    }
  }
}
