# MinCore

A small, opinionated core for Fabric Minecraft servers that ships every first-party capability as built-in modules server owners toggle on or off. MinCore bundles database access with safe schema evolution, wallets with a durable ledger, events, a simple scheduler, playtime, i18n, and timezone rendering so operators can right-size the surface area without juggling extra jars. The focus is on operators who want a cohesive, production-grade toolkit without stitching together extra downloads.

> Source of truth: **MinCore Master Spec v1.0.0**. Treat that spec as authoritative.

---

## Table of Contents

- [What is MinCore](#what-is-mincore)
- [Features](#features)
- [Requirements](#requirements)
- [Quick Start for Server Owners](#quick-start-for-server-owners)
  - [1. Install MariaDB](#1-install-mariadb)
  - [2. Create database and user](#2-create-database-and-user)
  - [3. Place JDBC driver](#3-place-jdbc-driver)
  - [4. Install the mod](#4-install-the-mod)
  - [5. Configure](#5-configure)
  - [6. First run smoke test](#6-first-run-smoke-test)
  - [7. Backups and retention](#7-backups-and-retention)
- [Configuration Reference](#configuration-reference)
- [Commands](#commands)
  - [/timezone](#timezone)
  - [/playtime](#playtime)
  - [/mincore db](#mincore-db)
  - [/mincore ledger](#mincore-ledger)
  - [Jobs and backups](#jobs-and-backups)
  - [Admin only extended](#admin-only-extended)
- [Backups, Export and Restore](#backups-export-and-restore)
- [Operational Runbooks](#operational-runbooks)
- [Security and Privacy](#security-and-privacy)
- [Developer Guide](#developer-guide)
  - [Project layout and toolchain](#project-layout-and-toolchain)
  - [Build and test](#build-and-test)
  - [Run a dev server](#run-a-dev-server)
  - [Module API overview](#module-api-overview)
  - [Database maintenance tasks](#database-maintenance-tasks)
  - [Event flow and ordering](#event-flow-and-ordering)
  - [Localization](#localization)
  - [Style and quality](#style-and-quality)
  - [Contributing](#contributing)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [License](#license)

---

## What is MinCore

MinCore is a server side Fabric mod that provides production grade modules designed to work together out of the box. It ships as a single "bundle-everything" jar that includes all first-party modules—database/migrations, wallet + ledger, scheduler/jobs, playtime tracking, timezone helpers, i18n, backup/export/import, and operator tooling. Server owners flip those modules on or off with configuration switches instead of juggling separate downloads. The core is small. The contracts are strong.

MinCore does not include a full gameplay economy, shops, quests, or a web panel. Pair it with whichever gameplay mods or datapacks you prefer.

## Features

- MariaDB first persistence via HikariCP (always on)
- SchemaHelper that performs ensure table, ensure column, ensure index, and safe check constraints
- Wallets API with idempotent deposit, withdraw, transfer and a durable ledger module that can be disabled when persistence is not desired
- Post commit events with at least once delivery and per player ordering guarantees
- Scheduler with cron like jobs in UTC and configurable job toggles (backup, cleanup, custom extensions)
- Daily JSONL exports with gzip and checksum files controlled by the backup module switches
- Playtime service with top, me and reset endpoints bundled in the core
- i18n and timezone rendering utilities, including optional player overrides and GeoIP auto-detect
- LuckPerms-first permission helper with Fabric Permissions API and vanilla fallbacks
- Config Template Writer that generates a complete commented example plus module toggle cheatsheet
- Single bundle shipping every first-party module; no extra jars to install

## Requirements

- Minecraft Fabric 1.21.8 server
- Java 21 LTS
- Fabric Loader with Loom 1.11.x for builds
- MariaDB 10.6 or newer recommended. MySQL 8.0 or newer supported
- Character set utf8mb4 and InnoDB tables
- JDBC driver `mariadb-java-client` available on the server classpath or inside the `mods` folder

## Quick Start for Server Owners

### 1. Install MariaDB

Docker example:

```bash
docker run --name mincore-mariadb -p 3306:3306 -e MARIADB_ROOT_PASSWORD=devroot -d mariadb:11
```

Native packages work as well. Make sure networking and firewall allow your server to connect.

### 2. Create database and user

Connect to the database and run:

```sql
CREATE DATABASE IF NOT EXISTS mincore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mincore'@'%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON mincore.* TO 'mincore'@'%';
FLUSH PRIVILEGES;
```

For production, replace `%` with your server subnet like `10.0.%`.

### 3. Place JDBC driver

MinCore keeps the core jar small. Provide the MariaDB driver yourself. Place `mariadb-java-client-<version>.jar` in the server `mods` folder or on the JVM classpath so the mod can load it.

### 4. Install the mod

Copy the MinCore jar into `mods`. Start the server once. The mod will generate `config/mincore.json5` and a `mincore.json5.example` template if missing.

### 5. Configure

Edit `config/mincore.json5`. Example with comments is generated for you. You can override sensitive values with environment variables:

- `MINCORE_DB_HOST`
- `MINCORE_DB_PORT`
- `MINCORE_DB_DATABASE`
- `MINCORE_DB_USER`
- `MINCORE_DB_PASSWORD`

Keep `session.forceUtc: true`. This sets the session time zone to UTC on every pooled connection. Use this step to decide which bundled modules stay enabled—disable the ledger if you only need wallets in memory, toggle the backup or cleanup jobs, and decide whether timezone overrides or GeoIP auto-detect should be available to players. If you plan to enable GeoIP auto-detect, set `core.time.display.allowPlayerOverride: true` before flipping `modules.timezone.autoDetect.enabled`; doing so automatically turns on the matching `core.time.display.autoDetect` flag. Operators migrating older configs should only keep the legacy `core.time.display.autoDetect` toggle if they need to override that default for compatibility.

### 6. First run smoke test

Start the server. Run these in game or on the server console as an operator.

1. `/mincore db ping`
2. `/mincore db info`
3. `/mincore diag`
4. Create two test players or use two accounts. Use wallet operations via the built-in wallet module (for example with the scripted utilities described in [Module API overview](#module-api-overview)), then check `/mincore ledger recent` *(only when `modules.ledger.enabled` is `true`; if the module is disabled, note that configuration choice and skip so the missing command output isn't mistaken for a failure).*
5. `/playtime me` and `/playtime top 10`

If all commands respond as expected, the core is healthy.

### 7. Backups and retention

By default, a JSONL backup job runs daily at 04:45 UTC and writes to `./backups/mincore`. Files are gzipped and a `.sha256` checksum is generated per file. Old backups are pruned using `keepDays` and `keepMax`. You can also trigger a manual backup with `/mincore backup now`.

## Configuration Reference

Config file location: `config/mincore.json5`

### Module toggles at a glance

| Module | Purpose | Default | Toggle(s) |
| ------ | ------- | ------- | --------- |
| Core runtime (DB, migrations, wallet engine, events) | Fundamental plumbing used by all other modules | Enabled | Always on |
| Ledger persistence | Durable append-only ledger for wallet changes and custom log entries | Enabled | `modules.ledger.enabled`; optional JSONL mirror via `modules.ledger.file.enabled` |
| Backup exporter | Scheduled JSONL backups with gzip + checksum | Enabled | `modules.scheduler.enabled` must remain `true`; toggle executions with `modules.scheduler.jobs.backup.enabled` |
| Idempotency sweep | TTL cleanup for the wallet request registry | Enabled | `modules.scheduler.jobs.cleanup.idempotencySweep.enabled` (requires `modules.scheduler.enabled`) |
| Playtime tracker | In-memory tracking with `/playtime` commands | Enabled | `modules.playtime.enabled` |
| Timezone services | `/timezone` commands, overrides, clock format, GeoIP detection | Enabled | `modules.timezone.enabled`; GeoIP lookup via `modules.timezone.autoDetect.enabled` + `modules.timezone.autoDetect.database` |
| i18n bundle | Locale loading/rendering | Enabled | Ensure `core.i18n.enabledLocales` lists allowed locales; remove extras to limit availability |

All modules ship in the single MinCore jar. Disabling a module removes its storage writes and scheduled jobs while keeping API methods safe to call (operations become no-ops where applicable). The top-level `modules: { ... }` block in `mincore.json5` controls these toggles and captures per-module settings (for example, backup schedules or the GeoIP database path).

```json5
{
  modules: {
    ledger: {
      enabled: true,
      retentionDays: 0,
      file: {
        enabled: false,
        path: "./logs/mincore-ledger.jsonl"
      }
    },
    scheduler: {
      enabled: true,
      jobs: {
        backup: {
          enabled: true,
          schedule: "0 45 4 * * *",
          outDir: "./backups/mincore",
          onMissed: "runAtNextStartup",
          gzip: true,
          prune: { keepDays: 14, keepMax: 60 }
        },
        cleanup: {
          idempotencySweep: {
            enabled: true,
            schedule: "0 30 4 * * *",
            retentionDays: 30,
            batchLimit: 5000
          }
        }
      }
    },
    timezone: {
      enabled: true,
      autoDetect: {
        enabled: false,
        database: "./config/mincore.geoip.mmdb"
      }
    },
    playtime: { enabled: true }
  },
  core: {
    db: {
      host: "127.0.0.1",
      port: 3306,
      database: "mincore",
      user: "mincore",
      password: "change-me",
      tls: { enabled: false },
      session: { forceUtc: true },
      pool: {
        maxPoolSize: 10,
        minimumIdle: 2,
        connectionTimeoutMs: 10000,
        idleTimeoutMs: 600000,
        maxLifetimeMs: 1700000,
        startupAttempts: 3
      }
    },
    runtime: { reconnectEveryS: 10 },
    time: {
      display: {
        defaultZone: "UTC",
        allowPlayerOverride: false,
        autoDetect: false
      }
    },
    i18n: {
      defaultLocale: "en_US",
      enabledLocales: [ "en_US" ],
      fallbackLocale: "en_US"
    },
    log: {
      json: false,
      slowQueryMs: 250,
      level: "INFO"
    }
  }
}
```

Notes

- Set `allowPlayerOverride: true` to allow `/timezone set <ZoneId>` for players
- Enable `modules.timezone.autoDetect.enabled` (and drop a GeoIP database at `modules.timezone.autoDetect.database`) to detect each joining player’s timezone automatically. Enabling the module sets `core.time.display.autoDetect: true` for you—just confirm `core.time.display.allowPlayerOverride: true` first. Keep the legacy `core.time.display.autoDetect` toggle only if you are migrating an older config that still needs it.
- Keep `forceUtc: true` so storage is consistent
- If you disable `modules.scheduler.enabled`, also set every job under `modules.scheduler.jobs` to `enabled: false`

## Commands

### /timezone

- `/timezone` shows help with the player’s zone, time zone label (e.g., PST (UTC-08:00)), clock style, and a formatted sample
- `/timezone set <ZoneId>` allows a player to set a personal time zone when overrides are enabled
- `/timezone clock <12|24>` lets players pick a 12-hour or 24-hour clock without changing their zone

Errors: `mincore.err.tz.invalid`, `mincore.err.tz.clockInvalid`, `mincore.err.tz.overridesDisabled`

### /playtime

- `/playtime me` shows your tracked playtime
- `/playtime top [N]` shows the top N players by playtime
- `/playtime reset <player>` admin only

### /mincore db

- `/mincore db ping` returns driver and server versions and round trip time
- `/mincore db info` shows pool stats, URL host and port, TLS flag, isolation level
- `/mincore diag` runs a quick health check including schema version and advisory locks

### /mincore ledger

- `/mincore ledger recent [N]`
- `/mincore ledger player <name|uuid> [N]`
- `/mincore ledger addon <id> [N]` *(filters by the module identifier recorded with each entry; bundled modules include their own identifier when logging)*
- `/mincore ledger reason <substring> [N]`

Timestamps render in viewer time zone. Outputs use i18n keys such as `mincore.cmd.ledger.header` and `mincore.cmd.ledger.line`.

### Jobs and backups

- `/mincore jobs list`
- `/mincore jobs run <job>`
- `/mincore backup now`

### Admin only extended

- `/mincore migrate --check` or `--apply`
- `/mincore export --all [--out <dir>] [--gzip true]`
- `/mincore restore --mode <fresh|merge> [--atomic|--staging] --from <dir>`
- `/mincore doctor [--fk --orphans --counts --analyze --locks]`

## Backups, Export and Restore

The built in exporter writes JSONL files that represent snapshot consistent tables with a stable schema. Each file has a `.gz` and a `.sha256` checksum. Use the importer to restore or merge data.

Manual export

```bash
/mincore export --all --out ./backups/mincore --gzip true
```

Restore fresh into an empty database

```bash
/mincore restore --mode fresh --atomic --from ./backups/mincore/2025-09-25
```

Merge with overwrite semantics

```bash
/mincore restore --mode merge --overwrite --from ./backups/mincore/2025-09-25
```

For large servers, consider `mariadb-dump` or `mariabackup` for point in time recovery. The JSONL export is best for portability and small to medium sized servers.

## Operational Runbooks

- **Ad hoc export** use the command above and verify checksums
- **Validate** run `/mincore doctor --counts --fk` to check referential integrity
- **Idempotency sweep** is automatic based on `retentionDays`. You can also run `/mincore jobs run cleanup.idempotencySweep`
- **Schema migrations** run `/mincore migrate --apply` on upgrade
- **Logs** enable JSON logs if you want machine readable output for ingestion

## Security and Privacy

- Use least privilege grants only on the target schema
- Store secrets with environment variables or an external secrets manager
- Use TLS between server and database when they run on different hosts
- MinCore does not collect PII by default
- Time zone auto detection is opt in and, when enabled, runs for every joining player

## Developer Guide

### Project layout and toolchain

- Java 21, Gradle 8.1.4 or newer, Loom 1.11.x
- Primary module `mincore`. Optional split like `mincore-jdbc` is acceptable if you keep the bundle jar simple
- Config at `config/mincore.json5`. Backups at `./backups/mincore`. Optional logs at `./logs/mincore.jsonl`

### Build and test

```bash
./gradlew clean build
```

Run tests and style checks. The project uses Spotless and Google Java Style. Keep zero warnings when possible.

### Run a dev server

```bash
./gradlew runServer
```

Provide a local MariaDB as shown earlier. The mod will create the schema automatically on first start.

### Module API overview

All bundled modules call into the shared `MinCoreApi` so their behavior stays consistent whether you invoke a command or let a scheduled job run. Operators who automate maintenance (for example with scripting mods or console bridges) can reuse the same entry points:

```java
var wallets = MinCoreApi.wallets();
var ledger  = MinCoreApi.ledger();

var playerId = someUuid;
boolean ok = wallets.deposit(playerId, 500, "welcome_bonus", "idem:welcome:player:" + playerId);
if (ok) {
  ledger.log("core.setup", "bonus", null, playerId, 500, "welcome_bonus", true, "OK", "welcome", "idem:welcome:player:" + playerId, "{}");
}
```

This mirrors the pattern MinCore uses internally when a built-in module posts an initial balance or reconciles a ledger entry. The idempotency key ensures repeated runs from automation or retries remain safe, and the ledger log gives operators a durable audit trail.

### Database maintenance tasks

Built-in diagnostics rely on the shared database pool exposed via `MinCoreApi.database()`. When you need to run a manual statement—such as validating a data fix from `/mincore doctor`—borrow a connection and let the helper handle retries and advisory locks:

```java
try (var conn = MinCoreApi.database().borrowConnection()) {
  // perform read or write using prepared statements
}
```

Use the `withRetry` helpers for deadlocks and timeouts that are safe to retry so your manual interventions match MinCore’s operational posture.

### Event flow and ordering

Events are delivered off thread after commit. Delivery is at least once and ordered per player. Built-in modules deduplicate with idempotency keys when side effects matter, and operators should follow the same approach for any scripted automations. Hop to the main thread before interacting with the world.

### Localization

Add locale files at `assets/mincore/lang/*.json`. Provide `en_us.json` as the default. Run the validation task to detect missing or mismatched keys.

### Style and quality

- Spotless with Google Java Style
- JavaDoc on all public APIs with clear invariants
- Error codes are mapped to i18n keys
- Use Conventional Commits for PRs and branch naming

### Contributing

Open issues and small focused pull requests. Do not commit secrets. Use code ownership and reviews for sensitive parts like schema or idempotency.

## Troubleshooting

- **Cannot connect to DB** check host, port, firewall, and credentials. Verify the DB driver jar is present
- **Wrong time zone rendering** check that `session.forceUtc: true` and that your server default zone and player overrides are configured correctly
- **Backups did not run** check the scheduler configuration and server clock. Run `/mincore jobs list` and `/mincore jobs run backup`
- **Idempotency conflicts** if you see replay or mismatch errors, confirm the triggering module or automation uses stable idempotency keys that include scope and normalized payload details
- **Large ledger table** set `retentionDays` to a positive number to enable truncation of very old entries or rely on external archival

## FAQ

**Can I use SQLite**  
No. MinCore targets MariaDB and MySQL only.

**Can I run without adding a JDBC driver jar**  
No. You must provide `mariadb-java-client` on the classpath or inside `mods`.

**Does MinCore include a shop or economy rules**
No. Pair MinCore with whichever gameplay mod or datapack you prefer for those features.

**Will this work on ARM**  
Yes. Java 21 ARM builds and MariaDB ARM images are supported.

## License

This project is licensed under the terms in the repository. See the `LICENSE` file.
