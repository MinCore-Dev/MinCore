# AGENTS.md — MinCore v1.0.0 (Unified & Consistent)

This document is the machine-operable specification for MinCore v1.0.0. Treat it as the single source of truth unless the user overrides it. It unifies all project requirements, standards, and runbooks so future agents can build and maintain MinCore's built-in modules without having to rediscover expectations from the codebase.

> Scope: Fabric Minecraft server mod named **MinCore** that provides DB access/migrations, economy wallets + ledger (with idempotency), events, scheduler, playtime, i18n, timezone rendering, JSONL backup/export/import, and ops tooling.

---

## Quick Reference for Agents

### 0) Agent Operating Contract

**Primary Objective**

Implement, configure, validate, operate, or extend MinCore according to this document. Prefer **safe, idempotent, ops-first** decisions.

**Never do**

* Block Minecraft main thread with I/O.
* Write destructive DB migrations in minors.
* Store non-UTC timestamps.
* Leak SQL exceptions to public APIs.
* Collect PII by default.

**Always do**

* Use **UTC** for storage; render with server or per-player TZ.
* Use **idempotent** wallet methods for network/retry contexts.
* Emit **structured logs** with error codes; rate-limit noisy failures.
* Use advisory locks for cluster-safe jobs/migrations.
* Validate config and bounds; fail fast on invalid settings.

### 1) Implementation Checklist

* [ ] Off-thread JDBC for all DAO paths.
* [ ] Post-commit events with per-player sequence ordering.
* [ ] Idempotent wallet variants with `core_requests` registry and payload hashing.
* [ ] Guarded SQL for withdraw/transfer (no negative balances).
* [ ] Ledger DB table + optional JSONL mirror with indexes.
* [ ] Config validation (bounds + required fields) and JSON5 parsing.
* [ ] Advisory-locked scheduler jobs (backup, cleanup).
* [ ] Backup exporter (consistent snapshot, gzip, checksums) + importer (fresh/merge modes).
* [ ] Error mapping to codes; structured logs; rate-limited warnings.
* [ ] I18n resource files and per-player TZ rendering when enabled.

### 2) Ops Checklist

* [ ] DB user least-priv; TLS if cross-host.
* [ ] `session.forceUtc=true`; backups enabled 04:45 UTC; retention configured.
* [ ] `/mincore db ping|info` OK; `/mincore diag` OK.
* [ ] `jobs list` shows backup & cleanup; advisory locks tested.
* [ ] Export and restore tested; checksums verified.
* [ ] Logs monitored for `CONNECTION_LOST`, `IDEMPOTENCY_*`, deadlocks.

### 3) Module Operations Checklist

* [ ] Honor module enable/disable toggles; provide explicit no-op behavior when disabled.
* [ ] Use idempotent wallet APIs for module-initiated network flows.
* [ ] Subscribe to core events responsibly; dedupe by (player, seq) when reacting.
* [ ] Use `ModuleDatabase.tryAdvisoryLock` (module-owned migrations/jobs) or the module-specific wrapper.
* [ ] Localize user messages; use server/player TZ render helpers.
* [ ] Avoid blocking main thread; schedule async work when needed.
* [ ] Emit structured health metrics and surface degradations via `/mincore diag`.

### 4) Error Codes (Canonical Set)

`INSUFFICIENT_FUNDS`, `INVALID_AMOUNT`, `UNKNOWN_PLAYER`, `IDEMPOTENCY_REPLAY`, `IDEMPOTENCY_MISMATCH`, `DEADLOCK_RETRY_EXHAUSTED`, `CONNECTION_LOST`, `DEGRADED_MODE`, `MIGRATION_LOCKED`, `NAME_AMBIGUOUS`, `INVALID_TZ`, `INVALID_CLOCK`, `OVERRIDES_DISABLED`.

### 5) Command Reference (One-Page)

* `/timezone` help; `/timezone set <ZoneId>`; `/timezone clock <12|24>`
* `/mincore db ping|info`; `/mincore diag`
* `/mincore ledger recent [N] | player <name|UUID> [N] | module <id> [N] | reason <substring> [N]`
* `/playtime me | top [N] | reset <player>`
* `/mincore jobs list | run <job>`; `/mincore backup now`
* Admin-only: `/mincore migrate --check|--apply` • `/mincore export --all [--out <dir>] [--gzip true]` • `/mincore restore --mode <fresh|merge> [--atomic|--staging] --from <dir>` • `/mincore doctor [--fk --orphans --counts --analyze --locks]`

**Rate-limits:** Player cmds cooldown 2–5s; admin diag token bucket (~cap 3–5, refill 0.2–0.5/s).

### 6) Data Retention & Integrity Rules

* No auto-prune for `players` or `core_ledger`.
* `core_requests` pruned by job only; player/ledger data unaffected.
* All timestamps UTC seconds; store UUIDs as `BINARY(16)`; JSON values valid and ≤ ~8KB.
* Withdraw/transfer must never permit negative balances; use conditional updates or transactions.

### 7) Minimal GRANT & Docker

See Sections 3.9 and 5.2 below for SQL and Docker snippets.

---

## Full Project Specification (verbatim master spec)

# MinCore Master Spec (v1.0.0) — Unified & Consistent

> This is the consolidated, internally consistent master specification for **MinCore v1.0.0**.  
> It resolves prior naming/terminology clashes (e.g., **backup vs export**, **db info vs db status**), aligns commands/APIs across all parts, and improves section flow.  
> Treat this as the **sole source of truth** unless you explicitly override it.

---

## Part 1 — Product, Scope & Compatibility

### 1.1 Mission

MinCore is a **small, opinionated core** for Fabric Minecraft servers that ships as a single jar containing first‑party modules—**DB access + schema evolution, wallets + ledger, events, scheduler, playtime, i18n, timezone rendering**—each controlled by configuration toggles so operators can right‑size the surface area without losing API stability. The mission is to keep those modules cohesive, ops‑friendly, and always invokable (no‑op when disabled) so servers get consistent behavior whether a feature is active or parked.

### 1.2 Non‑Goals

- Full gameplay mod (economy rules, shops, quests) — out of scope for built-in modules.
- Web UI / hosted panel.
- Cross‑DB abstraction beyond **MariaDB/MySQL**.
- Default PII collection (e.g., IPs). Optional, explicit, documented only.
- Replacement for heavy schedulers/message buses.
- Analytics pipeline (we provide a **ledger mirror** for external tooling).

### 1.3 Design Principles

1) **Small core, strong contracts** • 2) **Ops‑first defaults** • 3) **I18n + TZ everywhere** • 4) **Idempotent by design** • 5) **Predictable failure behavior** • 6) **Zero‑surprises migrations** • 7) **Observability > Guesswork**.

### 1.4 Value Proposition of the Bundled Modules

- **Single deployment artifact** with per‑module toggles—operators disable features they do not need without chasing separate jars.
- **Database** foundation shared by every module: HikariCP pool + **SchemaHelper** for safe, idempotent DDL (ensure‑table/index/column/check).
- **Wallets + Ledger module**: deposit/withdraw/transfer with **idempotency keys**, persisted ledger, and optional **JSONL mirror** even when the ledger output is paused.
- **Events module**: post‑commit, background dispatch with **per‑player ordering** so dependent modules stay in sync.
- **Scheduler module**: cron‑like UTC jobs driving **backup** and retention workflows that can be flipped off while leaving APIs callable.
- **Playtime module**: in‑memory tracker that exposes counters regardless of toggle state (disabled = fixed zeroes).
- **Localization + Timezone module**: helpers for I18n, timezone rendering, and optional GeoIP auto‑detect that reduce boilerplate for downstream operator workflows and automation.

### 1.5 Bundled Modules & Toggles

| Module | Primary Config Toggle | Notes & Disabled Behavior |
|---|---|---|
| Ledger | `modules.ledger.enabled` | Controls ledger persistence and JSONL mirroring. When disabled, wallet APIs still accept calls; ledger writes become no‑ops and readers receive empty/placeholder responses rather than failures. |
| Scheduler | `modules.scheduler.enabled` (with nested job toggles such as `modules.scheduler.jobs.backup.enabled`) | Disables background job execution while keeping scheduling APIs, job metadata, and command surfaces callable. Jobs report a disabled status when invoked directly. |
| Timezone & I18n | `modules.timezone.enabled`, `modules.timezone.autoDetect.enabled` | Governs timezone customization and optional GeoIP detection. When disabled, helpers fall back to the server default zone and retain method contracts. |
| Playtime | `modules.playtime.enabled` | Stops accruing playtime metrics but continues to serve API calls (always returning zero durations/counters). |

> **Contributor rule:** Adding a new module means defining an explicit config toggle, documenting the disabled behavior, and ensuring every exposed API remains callable (no‑op instead of exception) when the toggle is `false`.

### 1.6 Roadmap Snapshot (v1.0.0 highlights)

- Commented JSON5 config; backups 04:45 UTC; least‑priv DB; **Config Template Writer**.
- Server TZ default, optional per‑player TZ; **/timezone**.
- Commands: `/mincore diag`, `/mincore db ping|info`, `/mincore ledger …`, `/playtime me|top|reset`, `/mincore jobs list|run`, `/mincore backup now`.
- Dev standards: JavaDoc, Spotless, error‑code catalogue, module maintenance workflows, smoke test.

### 1.7 Compatibility Matrix

| Area | Min/Target | Notes |
|---|---|---|
| **Minecraft (Fabric)** | 1.20+ (Fabric API) | Match repo branch specifics. |
| **Java** | **21 LTS** | Primary toolchain. |
| **Fabric Loader/Loom** | Loader compat; **Loom 1.11.x** | Follow buildscript. |
| **Database** | **MariaDB 10.6+** rec., **MySQL 8.0+** supp. | `utf8mb4`, InnoDB, `BINARY(16)` UUIDs. |
| **OS/Arch** | Linux x86_64 (primary), ARM64 (supported) | Windows/macOS for dev. |
| **Time** | UTC storage; TZ‑aware rendering | No DST in storage. |

**UUIDs** stored as `BINARY(16)` for compact, fast indexes.

### 1.8 Versioning & Support

- **APIs** behave semantically (no breaking in patch; minimize in minor).
- **Migrations** are idempotent ensure‑ops; staged constraints.
- **Security fixes** may bypass deprecation timelines.

### 1.9 Glossary

Module • Services • Wallets • Ledger • Idempotency • SchemaHelper • Scheduler/Job • Playtime • I18n • TZ Rendering • Config Template Writer.

---

## Part 2 — Architecture & Persistence (MariaDB‑First)

### 2.1 High‑Level Architecture

- **Services** container exposes `Players`, `Wallets`, `Attributes`, `CoreEvents`, `ModuleDatabase`, `Playtime`, `Ledger`.
- **All DB I/O off‑thread**; **events post‑commit**; **per‑player ordered**.

### 2.2 Threading & Ordering

- Never block main thread with JDBC.
- Events: background threads, **at‑least‑once**, ordered per player; modules must dedupe and hop to main thread to touch world.

### 2.3 Resilience

- **DEGRADED mode** when DB down (writes refuse; reconnect every `runtime.reconnectEveryS`).  
- **withRetry** for deadlocks/timeouts.  
- **Idem storm** logs are rate‑limited.

### 2.4 Schema (DDL excerpts; rationale)

**`core_schema_version`**, **`players`** (`uuid BINARY(16)`, `name`, `name_lower` generated, `balance_units`, `*_at_s` UTC seconds),  
**`player_attributes`** (JSON ≤ ~8KB; `JSON_VALID` + length CHECK),  
**`core_requests`** (idempotency: `key_hash`, `scope`, `payload_hash`, `ok`, `created_at_s`, `expires_at_s`),  
**`player_event_seq`** (per‑player seq).

**Ledger `core_ledger`** (append‑only): `ts_s`, `module_id`, `op`, `from_uuid`, `to_uuid`, `amount`, `reason`, `ok`, `code`, `seq`, `idem_scope`, `idem_key_hash`, `old_units`, `new_units`, `server_node`, `extra_json`; indexed on time/module/op/from/to/reason/seq.

### 2.5 Idempotency (exact‑once)

Canonical payload for hash: `scope | fromUUID | toUUID | amount | reasonNorm`.  
Flow: insert request → compare on conflict (detect **MISMATCH**) → guarded updates (no negative balance) → mark ok → commit → emit event.

### 2.6 Indexing Strategy

Purposeful indexes on lookups/time windows/idempotency expiry.

### 2.7 JSONL Ledger Mirror

`jsonl/v1` lines with fields: `ts, module, op, from, to, amount, reason, ok, code, seq?, idemScope?, oldUnits?, newUnits?, extra?`.
Operators rotate/compress externally; backups can archive.

### 2.8 Performance Notes

HikariCP tuning, single‑snapshot export, minimal indexes; checksum `.sha256` alongside exports.

### 2.9 Retention

- No auto‑prune for players/ledger.  
- **core_requests** pruned by job (never touches business data).

### 2.10 Deliverables

ERD/DDL appendix • JSONL line schema • retention cheat‑sheet.

---

## Part 3 — Configuration & Operations (Ops‑First)

### 3.0 Guarantees

UTC storage • least‑priv DB user • advisory‑locked jobs • portable JSONL export/import • verbose JSON5 config.

### 3.1 Config Location & Parsing

`config/mincore.json5` (JSON5 with comments/trailing commas).  
`session.forceUtc=true` runs `SET time_zone='+00:00'` per pooled connection.

### 3.2 Full Commented Config (example)

```hocon
core {
  db {
    host = "127.0.0.1"
    port = 3306
    database = "mincore"
    user = "mincore"
    password = "change-me"
    tls { enabled = false }
    session { forceUtc = true }
    pool {
      maxPoolSize = 10
      minimumIdle = 2
      connectionTimeoutMs = 10000
      idleTimeoutMs = 600000
      maxLifetimeMs = 1700000
      startupAttempts = 3
    }
  }
  runtime { reconnectEveryS = 10 }
  time {
    display {
      defaultZone = "UTC"
      allowPlayerOverride = false
      autoDetect = false
    }
  }
  i18n {
    defaultLocale = "en_US"
    enabledLocales = [ "en_US" ]
    fallbackLocale = "en_US"
  }
  jobs {
    backup {
      enabled = true
      schedule = "0 45 4 * * *"
      outDir = "./backups/mincore"
      onMissed = "runAtNextStartup"
      gzip = true
      prune { keepDays = 14, keepMax = 60 }
    }
    cleanup.idempotencySweep {
      enabled = true
      schedule = "0 30 4 * * *"
      retentionDays = 30
      batchLimit = 5000
    }
  }
  log {
    json = false
    slowQueryMs = 250
    level = "INFO"
  }
}
```

**Env overrides:** `MINCORE_DB_HOST|PORT|DATABASE|USER|PASSWORD`.

### 3.3 Config Template Writer

Generates/updates `mincore.json5.example` with full comments; never overwrites live config.

### 3.4 Timezones

UTC storage; server display TZ; optional per‑player `/timezone set <ZoneId>` (if enabled); optional GeoIP auto-detect applies to every joining player when enabled; `/timezone clock <12|24>` lets players pick their clock style.

### 3.5 Localization (i18n)

Locales in `assets/mincore/lang/*.json`. Fallbacks; validation task checks missing/mismatched keys/placeholders.

### 3.6 Scheduler (cron UTC)

6‑field cron; advisory locks to single‑run across nodes; logs start/finish and counts.

### 3.7 Backups vs DB‑Native

- **Built‑in JSONL export**: daily 04:45 UTC to `outDir`, gzip + `.sha256`, snapshot consistency, advisory lock, missed‑run policy.  
- **Importer**: modes `fresh --atomic` / `fresh --staging` / `merge --overwrite`; schema‑version checks; optional FK fast‑path.  
- **DB‑native**: recommend `mariadb-dump`/`mariabackup` for scale + PITR.

### 3.8 Observability

`(mincore)`‑prefixed logs; error tokens; slow‑query WARNs; optional JSON log stream; optional metrics (JMX/Prometheus).

### 3.9 Security & Privacy

Least‑priv GRANT; secrets via env; TLS for cross‑host DB; no default PII; IP‑to‑TZ only if enabled and disclosed.

### 3.10 Runbooks

- **Ad‑hoc export**: `/mincore export --all --out ./backups/mincore --gzip true`  
- **Restore**: `/mincore restore --mode fresh --atomic --from <dir>` or `--staging`; or `--mode merge --overwrite`.  
- **DB‑native**: `mariadb-dump` / `mariabackup`; PITR via binlogs.  
- **Validation**: compare counts, smoke tests, `/mincore doctor --counts --fk`.

**Appendix A — Minimal GRANT**

```sql
CREATE USER 'mincore'@'10.0.%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON mincore.* TO 'mincore'@'10.0.%';
FLUSH PRIVILEGES;
```

---

## Part 4 — APIs & Commands (Dev Contract)

### 4.1 Guarantees

SemVer intent • off‑thread DB • events: background, AT‑LEAST‑ONCE, ordered per player • timestamps = UTC seconds • currency = minor units (`long`).

### 4.2 Core Entry Points

```java
public final class MinCoreApi {
  public static void bootstrap(dev.mincore.core.Services s);
  public static void publishLedger(Ledger l);
  public static dev.mincore.api.Players players();
  public static dev.mincore.api.Wallets wallets();
  public static dev.mincore.api.Attributes attributes();
  public static dev.mincore.api.events.CoreEvents events();
  public static dev.mincore.api.storage.ModuleDatabase database();
  public static Ledger ledger();
}
```

```java
public interface Services {
  dev.mincore.api.Players players();
  dev.mincore.api.Wallets wallets();
  dev.mincore.api.Attributes attributes();
  dev.mincore.api.events.CoreEvents events();
  dev.mincore.api.storage.ModuleDatabase database();
  java.util.concurrent.ScheduledExecutorService scheduler();
  dev.mincore.api.Playtime playtime();
  void shutdown() throws java.io.IOException;
}
```

### 4.3 Subsystems

**Players**

```java
public interface Players {
  Optional<PlayerRef> byUuid(UUID uuid);
  Optional<PlayerRef> byName(String name);
  List<PlayerRef> byNameAll(String name);
  void upsertSeen(UUID uuid, String name, long seenAtS);
  void iteratePlayers(java.util.function.Consumer<PlayerRef> consumer);
}
public interface PlayerRef {
  UUID uuid(); String name();
  long createdAtS(); long updatedAtS(); Long seenAtS();
  long balanceUnits();
}
```

**Wallets (idempotent variants preferred)**

```java
public interface Wallets {
  long getBalance(UUID player);
  boolean deposit(UUID player, long amount, String reason);
  boolean withdraw(UUID player, long amount, String reason);
  boolean transfer(UUID from, UUID to, long amount, String reason);
  boolean deposit(UUID player, long amount, String reason, String idemKey);
  boolean withdraw(UUID player, long amount, String reason, String idemKey);
  boolean transfer(UUID from, UUID to, long amount, String reason, String idemKey);
}
```

**Attributes**

```java
public interface Attributes {
  Optional<String> get(UUID owner, String key);
  void put(UUID owner, String key, String jsonValue, long nowS);
  void remove(UUID owner, String key);
}
```

**Events (v1)**

```java
record BalanceChangedEvent(UUID player, long seq, long oldUnits, long newUnits, String reason, int version) {}
record PlayerRegisteredEvent(UUID player, long seq, String name, int version) {}
record PlayerSeenUpdatedEvent(UUID player, long seq, String oldName, String newName, long seenAtS, int version) {}
```

**Playtime**

```java
public interface Playtime extends AutoCloseable {
  void onJoin(UUID player); void onQuit(UUID player);
  long seconds(UUID player); void reset(UUID player);
  List<Playtime.Entry> top(int limit);
  Set<UUID> onlinePlayers();
  static String human(long seconds) { /* impl */ }
  record Entry(UUID player, long seconds) {
    public long secondsNonNegative(){ return Math.max(0, seconds); }
    public String playerString(){ /* resolve */ return ""; }
  }
}
```

**Ledger**

```java
public interface Ledger {
  void log(String moduleId, String op, UUID from, UUID to, long amount,
           String reason, boolean ok, String code,
           String idemScope, String idemKey, String extraJson);
}
```

**ModuleDatabase**

```java
public interface ModuleDatabase {
  java.sql.Connection borrowConnection() throws java.sql.SQLException;
  boolean tryAdvisoryLock(String name);
  void releaseAdvisoryLock(String name);
  <T> T withRetry(SQLSupplier<T> action) throws java.sql.SQLException;
}
```

### 4.4 Commands

**/timezone**
- `/timezone` — help (shows zone, timezone label with abbreviation + UTC offset, clock style, sample time).
- `/timezone set <ZoneId>` (or `/timezone <ZoneId>`) — set personal TZ if enabled.
- `/timezone clock <12|24>` — toggle per-player clock style.
Errors: `mincore.err.tz.invalid`, `mincore.err.tz.clockInvalid`, `mincore.err.tz.overridesDisabled`; Success: `mincore.cmd.tz.set.ok`, `mincore.cmd.tz.clock.ok`.

**/mincore db**  
- `/mincore db ping` — driver/server versions, RTT. (`mincore.cmd.db.ping.ok|fail`)
- `/mincore db info` — pool stats, URL host/port (masked), TLS, isolation. (`mincore.cmd.db.info.*`)
- `/mincore diag` — ping + schema version + advisory lock check. (`mincore.cmd.diag.ok|fail`)

**/mincore ledger**  
- `recent [N]`, `player <name|UUID> [N]`, `module <id> [N]`, `reason <substring> [N]`.
Output respects viewer TZ; i18n keys: `mincore.cmd.ledger.header|line|none`.

**/playtime**  
- `me`, `top [N]`, `reset <player>` (admin). Keys: `mincore.cmd.pt.*`.

**Jobs & backup**  
- `/mincore jobs list`, `/mincore jobs run <job>`  
- `/mincore backup now` (manual JSONL export with current config)

**Admin‑only (extended)**  
- `/mincore migrate --check|--apply`  
- `/mincore export --all [--out <dir>] [--gzip true]`  
- `/mincore restore --mode <fresh|merge> [--atomic|--staging] --from <dir>`  
- `/mincore doctor [--fk --orphans --counts --analyze --locks]`

### 4.5 Rate‑Limiting

- Player cmds: cooldown 2–5s.  
- Admin diag: token bucket (cap ~3–5, refill ~0.2–0.5/s).

### 4.6 Error Catalogue → i18n Keys

| Code | i18n key |
|---|---|
| INSUFFICIENT_FUNDS | `mincore.err.economy.insufficient` |
| INVALID_AMOUNT | `mincore.err.economy.invalid` |
| UNKNOWN_PLAYER | `mincore.err.player.unknown` |
| IDEMPOTENCY_REPLAY | `mincore.err.idem.replay` |
| IDEMPOTENCY_MISMATCH | `mincore.err.idem.mismatch` |
| DEADLOCK_RETRY_EXHAUSTED | `mincore.err.db.deadlock` |
| CONNECTION_LOST | `mincore.err.db.unavailable` |
| DEGRADED_MODE | `mincore.err.db.degraded` |
| MIGRATION_LOCKED | `mincore.err.migrate.locked` |
| NAME_AMBIGUOUS | `mincore.err.player.ambiguous` |
| INVALID_TZ | `mincore.err.tz.invalid` |
| INVALID_CLOCK | `mincore.err.tz.clockInvalid` |
| OVERRIDES_DISABLED | `mincore.err.tz.overridesDisabled` |

### 4.7 Output Examples

- `/timezone` help, `/mincore db ping`, `/mincore ledger recent`, `/playtime top N` — localized; timestamps in viewer TZ with zone abbreviations + UTC offsets + clock style.

---

## Part 5 — Developer Guide, Standards & Tooling

### 5.1 Toolchain & Layout

- Java 21, Gradle 8.1.4+, Loom 1.11.x, MariaDB driver 3.4.x.  
- Artifacts: `mincore` (core), `mincore-jdbc` (driver shim); ship as **bundle JAR**.  
- Paths: config `config/mincore.json5`, backups `./backups/mincore`, optional logs `logs/mincore.jsonl`.  
- Env overrides supported for DB creds.

### 5.2 Local MariaDB (Docker)

```bash
docker run --name mincore-mariadb -p 3306:3306 -e MARIADB_ROOT_PASSWORD=devroot -d mariadb:11
```

```sql
CREATE DATABASE IF NOT EXISTS mincore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mincore'@'%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON mincore.* TO 'mincore'@'%';
FLUSH PRIVILEGES;
```

### 5.3 Build & Run

`./gradlew clean build` • `./gradlew runServer` • minimal config shown in Part 3.

### 5.3a Permission Gateway (LuckPerms-first)

- **Detection order:** MinCore resolves permissions using LuckPerms (via `LuckPermsProvider` and the cached user data) first, then the Fabric Permissions API when available, and finally vanilla operator levels as the fallback. All lookups should execute on the server thread so LuckPerms user data is already cached.
- **No hard deps:** Keep LuckPerms and Fabric Permissions API as `compileOnly` dependencies. Ship MinCore without bundling either implementation jar.
- **Helper API:** Use `dev.mincore.perms.Perms` from commands and services. Example: `if (!Perms.check(player, "mincore.module.command", 4)) { /* deny */ }`. For off-thread work, hop back to the main thread and call `Perms.checkUUID(server, uuid, node, opLevel)` if needed.
- **Node naming:** Prefix permissions with `mincore.` or your module identifier, e.g. `mincore.admin.jobs.run` or `mincore.playtime.viewer.toggle`. Keep lowercase dot-separated segments.
- **Op levels:** Level `4` for full admin, `3` for high-trust staff, `2` for moderation, `0` for everyone. Choose the fallback that mirrors the command’s intended audience.

### 5.4 One‑Shot Smoke Test

1) `/mincore db ping|info`  
2) Create 2 players; deposit; transfer with idemKey; repeat transfer (expect **REPLAY** no‑op).  
3) `/mincore ledger recent` shows entries; events fired.  
4) `/mincore export --all --out ./backups/mincore --gzip true`  
5) Cleanup; `/mincore doctor --counts --fk`.

### 5.5 Testing Strategy

- Unit: normalization, codes, rate‑limit.  
- Integration: real MariaDB; contention; idempotency replay/mismatch; retention sweep.  
- CI: DB service; env‑based secrets; artifacts.

### 5.6 Style, Exceptions, JavaDoc

- Spotless + Google Java Style; 0 warnings.  
- Map exceptions → codes; don’t leak raw SQL.  
- **JavaDoc on all public APIs**; clear invariants; examples when useful.

### 5.7 Localization Workflow

- Keys under `assets/mincore/lang/*.json` (e.g., `en_us.json`).  
- Consistent naming; placeholders; validation task for missing/mismatched keys; translator notes in Markdown.

### 5.8 Release Engineering

- SemVer intent (API+schema).  
- Ship DDL + commented config example.  
- Pre‑release: tests green, smoke test, migrate up on empty/non‑empty DB, export+restore verification.

### 5.9 Backward‑Compat & Deprecations

- `@Deprecated` + JavaDoc + rate‑limited warn; keep ≥1 minor.  
- Schema additive in minors; destructive only in majors.  
- Transitional flags for behavior changes when needed.

### 5.10 Module Lifecycle & No‑Op Expectations

All built-in modules must support three phases: **bootstrap**, **active**, and **disabled**.

1. **Bootstrap** — Register config schema, commands, scheduled jobs, and database components. Validate configuration before enabling stateful behavior.
2. **Active** — Execute business logic with full telemetry (structured logs, metrics). Respect advisory locks around long-running jobs and ensure event handlers are idempotent.
3. **Disabled** — Commands should report module-disabled messages, scheduled jobs must unschedule or short-circuit, and services should return safe defaults or no-ops without throwing.

When toggles change at runtime, transition gracefully: flush pending tasks, release locks, and guarantee repeated enable/disable cycles remain safe. Surface module state through `/mincore diag` and expose health signals so operators know whether a module is dormant, degraded, or healthy.

### 5.11 Contributor Workflow

Branches (`development`), small PRs, Conventional Commits, PR template, code ownership for sensitive parts, no secrets in repo, TLS guidance.

### 5.12 Ops‑Grade Smoke Test (Extended)

- `/mincore migrate --apply`, `/mincore db info`  
- 2 players, deposit/transfer + replay test  
- `/mincore export …` → verify files  
- Cleanup via restore fresh/atomic or truncation  
- `/mincore doctor --counts --fk`

### 5.13 Deliverables (“Done”)

- **CONTRIBUTING.md**, style guide, module maintenance workflow, smoke test script, updated DDL/config example per release.

---

**End of MinCore v1.0.0 — Unified & Consistent Master Spec**
