/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import java.util.List;

final class DoctorOptions {
  final boolean fk;
  final boolean orphans;
  final boolean counts;
  final boolean analyze;
  final boolean locks;

  private DoctorOptions(boolean fk, boolean orphans, boolean counts, boolean analyze, boolean locks) {
    this.fk = fk;
    this.orphans = orphans;
    this.counts = counts;
    this.analyze = analyze;
    this.locks = locks;
  }

  static DoctorOptions parse(String raw) {
    boolean fk = false;
    boolean orphans = false;
    boolean counts = false;
    boolean analyze = false;
    boolean locks = false;

    AdminOptionParser parser = AdminOptionParser.from(raw);
    List<AdminOptionParser.ParsedOption<DoctorFlag>> parsed =
        parser.collect(DoctorFlag.values());
    for (AdminOptionParser.ParsedOption<DoctorFlag> option : parsed) {
      switch (option.option()) {
        case FK -> fk = true;
        case ORPHANS -> orphans = true;
        case COUNTS -> counts = true;
        case ANALYZE -> analyze = true;
        case LOCKS -> locks = true;
      }
    }

    if (parser.isEmpty() || (!fk && !orphans && !counts && !analyze && !locks)) {
      fk = true;
      counts = true;
    }

    return new DoctorOptions(fk, orphans, counts, analyze, locks);
  }

  boolean hasAny() {
    return fk || orphans || counts || analyze || locks;
  }

  private enum DoctorFlag implements AdminOptionParser.NamedOption {
    FK(List.of("--fk")),
    ORPHANS(List.of("--orphans")),
    COUNTS(List.of("--counts")),
    ANALYZE(List.of("--analyze")),
    LOCKS(List.of("--locks"));

    private final List<String> tokens;

    DoctorFlag(List<String> tokens) {
      this.tokens = tokens;
    }

    @Override
    public List<String> tokens() {
      return tokens;
    }
  }
}
