/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigValidationTest {

  @Test
  void invalidLedgerRetentionUsesModulesPath(@TempDir Path tempDir) throws IOException {
    Path configPath = tempDir.resolve("holarki.json5");
    Files.writeString(
        configPath,
        configJson(-1, "0 45 4 * * *"),
        StandardCharsets.UTF_8);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> Config.loadOrWriteDefault(configPath));

    assertEquals("modules.ledger.retentionDays must be >= 0", thrown.getMessage());
  }

  @Test
  void invalidBackupScheduleUsesModulesPath(@TempDir Path tempDir) throws IOException {
    Path configPath = tempDir.resolve("holarki.json5");
    Files.writeString(configPath, configJson(0, ""), StandardCharsets.UTF_8);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> Config.loadOrWriteDefault(configPath));

    assertEquals(
        "modules.scheduler.jobs.backup.schedule must be provided", thrown.getMessage());
  }

  @Test
  void preservesCommentMarkersInsideStrings(@TempDir Path tempDir) throws IOException {
    Path configPath = tempDir.resolve("holarki.json5");
    String config =
        configJson(0, "0 45 4 * * *")
            .replace("\"user\": \"holarki\"", "\"user\": \"holarki//primary\"")
            .replace("\"password\": \"change-me\"", "\"password\": \"pa/*ss*/word\"")
            .replace(
                "\"path\": \"./logs/holarki-ledger.jsonl\"",
                "\"path\": \"https://example.com/logs//file\"")
            + "\n// trailing comment\n";
    Files.writeString(configPath, config, StandardCharsets.UTF_8);

    Config parsed = Config.loadOrWriteDefault(configPath);

    assertEquals("holarki//primary", parsed.db().user());
    assertEquals("pa/*ss*/word", parsed.db().password());
    assertEquals("https://example.com/logs//file", parsed.ledger().jsonlMirror().path());
  }

  private static String configJson(int retentionDays, String backupSchedule) {
    return (
            """
            {
              "modules": {
                "ledger": {
                  "enabled": true,
                  "retentionDays": %d,
                  "file": {
                    "enabled": false,
                    "path": "./logs/holarki-ledger.jsonl"
                  }
                },
                "scheduler": {
                  "enabled": true,
                  "jobs": {
                    "backup": {
                      "enabled": true,
                      "schedule": "%s",
                      "outDir": "./backups/holarki",
                      "onMissed": "runAtNextStartup",
                      "gzip": true,
                      "prune": {
                        "keepDays": 14,
                        "keepMax": 60
                      }
                    },
                    "cleanup": {
                      "idempotencySweep": {
                        "enabled": true,
                        "schedule": "0 30 4 * * *",
                        "retentionDays": 30,
                        "batchLimit": 5000
                      }
                    }
                  }
                },
                "timezone": {
                  "enabled": true,
                  "autoDetect": {
                    "enabled": false,
                    "database": "./config/holarki.geoip.mmdb"
                  }
                },
                "playtime": {
                  "enabled": true
                }
              },
              "core": {
                "db": {
                  "host": "127.0.0.1",
                  "port": 3306,
                  "database": "holarki",
                  "user": "holarki",
                  "password": "change-me",
                  "tls": { "enabled": false },
                  "session": { "forceUtc": true },
                  "pool": {
                    "maxPoolSize": 10,
                    "minimumIdle": 2,
                    "connectionTimeoutMs": 10000,
                    "idleTimeoutMs": 600000,
                    "maxLifetimeMs": 1700000,
                    "startupAttempts": 3
                  }
                },
                "runtime": { "reconnectEveryS": 10 },
                "time": {
                  "display": {
                    "defaultZone": "UTC",
                    "allowPlayerOverride": false,
                    "autoDetect": false
                  }
                },
                "i18n": {
                  "defaultLocale": "en_US",
                  "enabledLocales": [ "en_US" ],
                  "fallbackLocale": "en_US"
                },
                "log": {
                  "json": false,
                  "slowQueryMs": 250,
                  "level": "INFO"
                }
              }
            }
            """
                .formatted(retentionDays, backupSchedule));
  }
}
