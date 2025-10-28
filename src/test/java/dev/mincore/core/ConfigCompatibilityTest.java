/* MinCore © 2025 — MIT */
package dev.mincore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for configuration backward compatibility. */
final class ConfigCompatibilityTest {

  @TempDir Path tempDir;

  @Test
  void loadLegacyConfigPreservesLedgerAndSchedules() throws Exception {
    Path configFile = tempDir.resolve("legacy.json5");
    Files.writeString(
        configFile,
        """
            {
              core: {
                db: {
                  host: \"127.0.0.1\",
                  port: 3306,
                  database: \"legacy\",
                  user: \"mincore\",
                  password: \"change-me\"
                },
                runtime: { reconnectEveryS: 5 },
                time: {
                  display: {
                    defaultZone: \"UTC\",
                    allowPlayerOverride: false,
                    autoDetect: false
                  }
                },
                i18n: {
                  defaultLocale: \"en_US\",
                  enabledLocales: [ \"en_US\" ],
                  fallbackLocale: \"en_US\"
                },
                ledger: {
                  enabled: true,
                  retentionDays: 42,
                  file: {
                    enabled: true,
                    path: \"./logs/legacy-ledger.jsonl\"
                  }
                },
                jobs: {
                  backup: {
                    enabled: true,
                    schedule: \"0 0 12 * * *\",
                    outDir: \"./backups/legacy\",
                    onMissed: \"runAtNextStartup\",
                    gzip: false,
                    prune: { keepDays: 10, keepMax: 7 }
                  },
                  cleanup: {
                    idempotencySweep: {
                      enabled: true,
                      schedule: \"0 15 1 * * *\",
                      retentionDays: 7,
                      batchLimit: 2000
                    }
                  }
                },
                log: { json: false, slowQueryMs: 250, level: \"INFO\" }
              }
            }
            """,
        StandardCharsets.UTF_8);

    Config config = Config.loadOrWriteDefault(configFile);

    assertTrue(config.modules().ledger().enabled());
    assertEquals(42, config.modules().ledger().retentionDays());
    assertTrue(config.modules().ledger().jsonlMirror().enabled());
    assertEquals(
        "./logs/legacy-ledger.jsonl", config.modules().ledger().jsonlMirror().path());
    assertTrue(config.modules().scheduler().enabled());
    assertEquals(
        "0 0 12 * * *", config.modules().scheduler().jobs().backup().schedule());
    assertEquals(
        "./backups/legacy", config.modules().scheduler().jobs().backup().outDir());
    assertEquals(
        Config.OnMissed.RUN_AT_NEXT_STARTUP,
        config.modules().scheduler().jobs().backup().onMissed());
    assertEquals(7, config.modules().scheduler().jobs().cleanup().idempotencySweep().retentionDays());
    assertEquals(2000, config.modules().scheduler().jobs().cleanup().idempotencySweep().batchLimit());
  }

  @Test
  void loadModulesConfigKeepsExplicitValues() throws Exception {
    Path configFile = tempDir.resolve("modules.json5");
    Files.writeString(
        configFile,
        """
            {
              modules: {
                ledger: {
                  enabled: true,
                  retentionDays: 15,
                  file: {
                    enabled: true,
                    path: \"./logs/modules-ledger.jsonl\"
                  }
                },
                scheduler: {
                  enabled: true,
                  jobs: {
                    backup: {
                      enabled: true,
                      schedule: \"0 30 2 * * *\",
                      outDir: \"./backups/modules\",
                      onMissed: \"skip\",
                      gzip: true,
                      prune: { keepDays: 14, keepMax: 5 }
                    },
                    cleanup: {
                      idempotencySweep: {
                        enabled: true,
                        schedule: \"0 45 3 * * *\",
                        retentionDays: 20,
                        batchLimit: 4000
                      }
                    }
                  }
                },
                timezone: {
                  enabled: true,
                  autoDetect: {
                    enabled: true,
                    database: \"./config/timezones.mmdb\"
                  }
                },
                playtime: { enabled: true }
              },
              core: {
                db: {
                  host: \"127.0.0.1\",
                  port: 3306,
                  database: \"modules\",
                  user: \"mincore\",
                  password: \"change-me\"
                },
                runtime: { reconnectEveryS: 5 },
                time: {
                  display: {
                    defaultZone: \"UTC\",
                    allowPlayerOverride: true,
                    autoDetect: true
                  }
                },
                i18n: {
                  defaultLocale: \"en_US\",
                  enabledLocales: [ \"en_US\" ],
                  fallbackLocale: \"en_US\"
                },
                log: { json: false, slowQueryMs: 250, level: \"INFO\" }
              }
            }
            """,
        StandardCharsets.UTF_8);

    Config config = Config.loadOrWriteDefault(configFile);

    assertEquals(15, config.modules().ledger().retentionDays());
    assertEquals(
        "0 30 2 * * *", config.modules().scheduler().jobs().backup().schedule());
    assertEquals(
        "./backups/modules", config.modules().scheduler().jobs().backup().outDir());
    assertEquals(20, config.modules().scheduler().jobs().cleanup().idempotencySweep().retentionDays());
    assertTrue(config.time().display().autoDetect());
    assertTrue(config.modules().timezone().autoDetect().enabled());
    assertEquals(
        "./config/timezones.mmdb", config.modules().timezone().autoDetect().databasePath());
  }

  @Test
  void backupDefaultsToEnabledWhenToggleMissing() throws Exception {
    Path configFile = tempDir.resolve("backup-default-enabled.json5");
    Files.writeString(
        configFile,
        """
            {
              modules: {
                scheduler: {
                  enabled: true,
                  jobs: {
                    backup: {
                      schedule: \"0 0 6 * * *\",
                      outDir: \"./backups/silent-default\"
                    }
                  }
                }
              },
              core: {
                db: {
                  host: \"127.0.0.1\",
                  port: 3306,
                  database: \"defaults\",
                  user: \"mincore\",
                  password: \"change-me\"
                },
                runtime: { reconnectEveryS: 5 },
                time: {
                  display: {
                    defaultZone: \"UTC\",
                    allowPlayerOverride: false,
                    autoDetect: false
                  }
                },
                i18n: {
                  defaultLocale: \"en_US\",
                  enabledLocales: [ \"en_US\" ],
                  fallbackLocale: \"en_US\"
                },
                log: { json: false, slowQueryMs: 250, level: \"INFO\" }
              }
            }
            """,
        StandardCharsets.UTF_8);

    Config config = Config.loadOrWriteDefault(configFile);

    assertTrue(config.modules().scheduler().jobs().backup().enabled());
    assertEquals(
        "0 0 6 * * *", config.modules().scheduler().jobs().backup().schedule());
    assertEquals(
        "./backups/silent-default", config.modules().scheduler().jobs().backup().outDir());
  }

  @Test
  void ledgerDefaultsToEnabledWhenToggleMissing() throws Exception {
    Path configFile = tempDir.resolve("ledger-default.json5");
    Files.writeString(
        configFile,
        """
            {
              modules: {
                ledger: {
                  retentionDays: 5,
                  file: { path: \"./logs/default-ledger.jsonl\" }
                },
                scheduler: {
                  enabled: false,
                  jobs: {
                    backup: {
                      enabled: false,
                      schedule: \"0 45 4 * * *\",
                      outDir: \"./backups/mincore\"
                    },
                    cleanup: {
                      idempotencySweep: {
                        enabled: false,
                        schedule: \"0 30 4 * * *\",
                        retentionDays: 30,
                        batchLimit: 5000
                      }
                    }
                  }
                }
              },
              core: {
                db: {
                  host: \"127.0.0.1\",
                  port: 3306,
                  database: \"defaults\",
                  user: \"mincore\",
                  password: \"change-me\"
                },
                runtime: { reconnectEveryS: 5 },
                time: {
                  display: {
                    defaultZone: \"UTC\",
                    allowPlayerOverride: false,
                    autoDetect: false
                  }
                },
                i18n: {
                  defaultLocale: \"en_US\",
                  enabledLocales: [ \"en_US\" ],
                  fallbackLocale: \"en_US\"
                },
                log: { json: false, slowQueryMs: 250, level: \"INFO\" }
              }
            }
            """,
        StandardCharsets.UTF_8);

    Config config = Config.loadOrWriteDefault(configFile);

    assertTrue(config.modules().ledger().enabled());
    assertEquals(5, config.modules().ledger().retentionDays());
    assertEquals("./logs/default-ledger.jsonl", config.modules().ledger().jsonlMirror().path());
  }
}
