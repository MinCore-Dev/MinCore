# AGENTS.md — MinCore v0.2.0 (Unified & Consistent)

This document is a **machine-operable specification** of MinCore v0.2.0 for code agents (e.g., Codex, tool-using LLMs).
It **fully encodes** the unified master spec, reorganized into **actionable sections**, **APIs**, **schemas**, **commands**, **rules**, **checklists**, and **step-by-step procedures**. Treat this as the **single source of truth** unless the user explicitly overrides it.

> Scope: Fabric Minecraft server mod named **MinCore** that provides DB access/migrations, economy wallets + ledger (with idempotency), events, scheduler, playtime, i18n, timezone rendering, JSONL backup/export/import, and ops tooling.

---

## 0) Agent Operating Contract

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

---

## 1) Product, Scope & Compatibility

### Mission

Small, opinionated core providing: **DB + migrations, Wallets + Ledger, Events, Scheduler, Playtime, I18n, TZ rendering**, JSONL backup/import.

### Non-Goals

Gameplay content; web UI; poly-DB targets; default PII; heavy schedulers/buses; analytics (we expose **ledger mirror** for external tools).

### Design Principles

1. Small core, strong contracts
2. Ops-first defaults
3. I18n + TZ everywhere
4. Idempotent by design
5. Predictable failure behavior
6. Zero-surprises migrations
7. Observability > Guesswork

### Value for Add-ons

SchemaHelper; Wallets (+idempotency); Ledger (+JSONL mirror); ordered post-commit Events; UTC Scheduler (+backup job); Playtime; I18n/TZ helpers.

### Roadmap (v0.2.0 highlights)

Commented JSON5; backup 04:45 UTC; least-priv DB; Config Template Writer; `/timezone`; `/mincore diag`, `/mincore db`, `/mincore ledger`, `/playtime`, `/mincore jobs ...`, `/mincore backup now`; dev standards (Spotless, JavaDoc, error codes), example add-on, smoke test.

### Compatibility Matrix

| Area               | Min/Target                             | Notes                                  |
| ------------------ | -------------------------------------- | -------------------------------------- |
| Minecraft (Fabric) | 1.20+                                  | Follow repo branch specifics.          |
| Java               | 21 LTS                                 | Primary toolchain.                     |
| Loom               | 1.11.x                                 | Match buildscript.                     |
| DB                 | MariaDB 10.6+ (rec), MySQL 8.0+ (sup)  | `utf8mb4`, InnoDB, `BINARY(16)` UUIDs. |
| OS/Arch            | Linux x86\_64 primary; ARM64 supported | Windows/macOS for dev.                 |
| Time               | UTC storage; TZ render                 | No DST in storage.                     |

UUIDs stored as `BINARY(16)`.

Versioning: APIs semver-like, schema idempotent ensure-ops. Security patches may bypass deprecations.

Glossary: Add-on, Services, Wallets, Ledger, Idempotency, SchemaHelper, Scheduler/Job, Playtime, I18n, TZ Rendering, Config Template Writer.

---

## 2) Architecture & Persistence

### Runtime Components

* **Services** container: `Players`, `Wallets`, `Attributes`, `CoreEvents`, `ExtensionDatabase`, `Playtime`, `Ledger`.
* **DB I/O off-thread**, **events post-commit**, **ordered per-player** (no cross-player order).

### Threading Rules

* No JDBC on main thread.
* Events at-least-once, dedupe by (player, seq).
* Listeners must hop to main thread to affect world.

### Resilience

* **DEGRADED** mode if DB down: refuse writes, `CONNECTION_LOST`, retry every `runtime.reconnectEveryS`.
* **withRetry** for deadlocks/timeouts; rate-limit idempotency storm logs.

### Core Schema (DDL excerpts)

`core_schema_version(version, applied_at_s)`

`players(uuid BINARY(16) PK, name, name_lower GENERATED, balance_units BIGINT UNSIGNED DEFAULT 0, created_at_s, updated_at_s, seen_at_s, idx name_lower, idx seen_at_s)`

`player_attributes(owner_uuid BINARY(16) FK→players, attr_key, value_json MEDIUMTEXT, created_at_s, updated_at_s, PK(owner_uuid,attr_key), CHECK JSON_VALID(value_json) AND length≤8192)`

`core_requests(key_hash BINARY(32), scope VARCHAR(64), payload_hash BINARY(32), ok TINYINT, created_at_s, expires_at_s, PK(key_hash,scope), idx expires_at_s)`

`player_event_seq(uuid BINARY(16) PK, seq BIGINT UNSIGNED)`

**Ledger: `core_ledger`**
`id PK AI, ts_s, addon_id, op, from_uuid?, to_uuid?, amount, reason, ok, code?, seq, idem_scope?, idem_key_hash?, old_units?, new_units?, server_node?, extra_json?`, indexes on ts/addon/op/from/to/reason/seq/idempotencyScope.

### Idempotency (Exact-Once)

Canonical payload = `scope|fromUUID|toUUID|amount|reasonNorm`.
Flow: insert into `core_requests` → on duplicate check `payload_hash` (mismatch ⇒ `IDEMPOTENCY_MISMATCH`) → perform guarded updates (no negative balance) → mark ok → commit → emit events.

### JSONL Ledger Mirror (jsonl/v1)

One line per ledger entry:
`{ ts, addon, op, from?, to?, amount, reason, ok, code?, seq?, idemScope?, oldUnits?, newUnits?, extra? }`
Operators rotate/compress; backups can archive; it’s supplementary (DB is source of truth).

### Indexing

Purposeful indexes: lookups by UUID, name\_lower; time/range queries; idempotency expiry; ledger filters (addon/op/reason/from/to/seq).

---

## 3) Configuration & Operations

### Guarantees

UTC storage, least-priv DB, advisory-locked jobs, portable JSONL export/import, JSON5 config.

### Config Location & Parsing

`config/mincore.json5` (JSON5).
`session.forceUtc=true` ⇒ `SET time_zone='+00:00'` on connection acquire.
Env overrides: `MINCORE_DB_HOST|PORT|DATABASE|USER|PASSWORD`.

### Full Example (JSON5/HOCON-style)

```hocon
core {
  db {
    host = "127.0.0.1"; port = 3306; database = "mincore"; user = "mincore"; password = "change-me"
    tls { enabled = false }
    session { forceUtc = true }
    pool { maxPoolSize=10; minimumIdle=2; connectionTimeoutMs=10000; idleTimeoutMs=600000; maxLifetimeMs=1700000; startupAttempts=3 }
  }
  runtime { reconnectEveryS = 10 }
  time.display { defaultZone="UTC"; allowPlayerOverride=false; autoDetect=false }
  i18n { defaultLocale="en_US"; enabledLocales=[ "en_US" ]; fallbackLocale="en_US" }
  jobs {
    backup { enabled=true; schedule="0 45 4 * * *"; outDir="./backups/mincore"; onMissed="runAtNextStartup"; gzip=true; prune { keepDays=14; keepMax=60 } }
    cleanup.idempotencySweep { enabled=true; schedule="0 30 4 * * *"; retentionDays=30; batchLimit=5000 }
  }
  log { json=false; slowQueryMs=250; level="INFO" }
}
```

**Validation bounds** (agents enforce in code paths):

* Required: host, database, user, password.
* Bounds: `maxPoolSize [1,50]`, `minimumIdle ≤ maxPoolSize`, `reconnectEveryS [5,300]`, `retentionDays [1,365]`, `batchLimit [100,100000]`.

### Template Writer

Generate/refresh `mincore.json5.example`; **never** overwrite live `mincore.json5`.

### Timezones

* Persist UTC; render via server default or per-player.
* `/timezone set <ZoneId>` (allowed only if `allowPlayerOverride=true`).
* Owner-only auto-detect (no player “auto” command). Privacy disclosure required if enabled.

### Scheduler

Cron = 6 fields (`sec min hour dom mon dow`) in **UTC**.
Advisory locks prevent multi-node duplication.
Log start/finish + counts + durations.

### Backups / Import / DB-Native

* **Built-in Export**: daily at 04:45 UTC; gzip + `.sha256`; consistent snapshot; advisory lock; missed-run policy.
* **Importer**: `fresh --atomic` (one-shot), `fresh --staging` (chunked, temp tables), `merge --overwrite`; schema version checks; FK fast-path optional.
* **DB-native**: recommend `mariadb-dump`/`mariabackup`; PITR via binlogs.

### Observability

`(mincore)`-prefixed logs; error tokens; slow-query WARN; optional JSON log stream; optional metrics.

### Security & Privacy

Least-priv GRANT; secret via env; TLS for cross-host DB; no PII by default; IP→TZ only if enabled and disclosed.

### Runbooks (Commands)

* Export now: `/mincore export --all --out ./backups/mincore --gzip true`
* Restore: `/mincore restore --mode fresh --atomic --from <dir>` or `--staging`; `--mode merge --overwrite`
* Validate: row counts + smoke tests + `/mincore doctor --counts --fk`

Minimal GRANT:

```sql
CREATE USER 'mincore'@'10.0.%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON mincore.* TO 'mincore'@'10.0.%';
FLUSH PRIVILEGES;
```

---

## 4) Public APIs & Commands (Exact Contract)

### Core Accessors

```java
public final class MinCoreApi {
  public static void bootstrap(dev.mincore.core.Services s);
  public static void publishLedger(Ledger l);
  public static dev.mincore.api.Players players();
  public static dev.mincore.api.Wallets wallets();
  public static dev.mincore.api.Attributes attributes();
  public static dev.mincore.api.events.CoreEvents events();
  public static dev.mincore.api.storage.ExtensionDatabase database();
  public static Ledger ledger();
}
public interface Services {
  dev.mincore.api.Players players();
  dev.mincore.api.Wallets wallets();
  dev.mincore.api.Attributes attributes();
  dev.mincore.api.events.CoreEvents events();
  dev.mincore.api.storage.ExtensionDatabase database();
  java.util.concurrent.ScheduledExecutorService scheduler();
  dev.mincore.api.Playtime playtime();
  void shutdown() throws java.io.IOException;
}
```

### Players

```java
public interface Players {
  Optional<PlayerRef> byUuid(UUID uuid);
  Optional<PlayerRef> byName(String name);      // case-insensitive
  List<PlayerRef> byNameAll(String name);
  void upsertSeen(UUID uuid, String name, long seenAtS);
  void iteratePlayers(java.util.function.Consumer<PlayerRef> c); // off-thread
}
public interface PlayerRef {
  UUID uuid(); String name();
  long createdAtS(); long updatedAtS(); Long seenAtS();
  long balanceUnits();
}
```

### Wallets (idempotent preferred)

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

**Validation & Errors**

* `amount > 0` required.
* No negative balances.
* Error codes (logged & localized):
  `INSUFFICIENT_FUNDS`, `INVALID_AMOUNT`, `UNKNOWN_PLAYER`, `IDEMPOTENCY_REPLAY`, `IDEMPOTENCY_MISMATCH`, `DEADLOCK_RETRY_EXHAUSTED`, `CONNECTION_LOST`, `DEGRADED_MODE`, `MIGRATION_LOCKED`, `NAME_AMBIGUOUS`.

### Attributes

```java
public interface Attributes {
  Optional<String> get(UUID owner, String key);
  void put(UUID owner, String key, String jsonValue, long nowS);
  void remove(UUID owner, String key);
}
```

### Events (v1; post-commit; per-player ordered; at-least-once)

```java
record BalanceChangedEvent(UUID player, long seq, long oldUnits, long newUnits, String reason, int version) {}
record PlayerRegisteredEvent(UUID player, long seq, String name, int version) {}
record PlayerSeenUpdatedEvent(UUID player, long seq, String oldName, String newName, long seenAtS, int version) {}
```

### Playtime

```java
public interface Playtime extends AutoCloseable {
  void onJoin(UUID player); void onQuit(UUID player);
  long seconds(UUID player); void reset(UUID player);
  List<Playtime.Entry> top(int limit);
  Set<UUID> onlinePlayers();
  static String human(long seconds);
  record Entry(UUID player, long seconds) {
    public long secondsNonNegative();
    public String playerString();
  }
}
```

### Ledger

```java
public interface Ledger {
  void log(String addonId, String op, UUID from, UUID to, long amount,
           String reason, boolean ok, String code,
           String idemScope, String idemKey, String extraJson);
}
```

### ExtensionDatabase

```java
public interface ExtensionDatabase {
  java.sql.Connection borrowConnection() throws java.sql.SQLException;
  boolean tryAdvisoryLock(String name);
  void releaseAdvisoryLock(String name);
  <T> T withRetry(SQLSupplier<T> action) throws java.sql.SQLException;
}
```

### Commands (Player/Admin)

**/timezone**

* `/timezone` help (shows current setting; notes owner-controlled auto-detect).
* `/timezone set <ZoneId>` (or `/timezone <ZoneId>`) – set personal TZ if enabled.
* Keys: success `mincore.cmd.tz.set.ok`; errors `mincore.err.tz.invalid`, `mincore.err.tz.overridesDisabled`.

**/mincore db**

* `ping` (RTT + versions) — `mincore.cmd.db.ping.ok|fail`
* `info` (pool + URL host/port masked + TLS + isolation) — `mincore.cmd.db.info.*`
* `/mincore diag` (ping + schema version + advisory lock test) — `mincore.cmd.diag.ok|fail`

**/mincore ledger**

* `recent [N]`, `player <name|UUID> [N]`, `addon <id> [N]`, `reason <substring> [N]`
* Output in viewer TZ. Keys: `mincore.cmd.ledger.header|line|none`.

**/playtime**

* `me`, `top [N]`, `reset <player>` (admin). Keys: `mincore.cmd.pt.*`.

**Jobs & backup**

* `/mincore jobs list`, `/mincore jobs run <job>`
* `/mincore backup now`

**Admin-only extended**

* `/mincore migrate --check|--apply`
* `/mincore export --all [--out <dir>] [--gzip true]`
* `/mincore restore --mode <fresh|merge> [--atomic|--staging] --from <dir>`
* `/mincore doctor [--fk --orphans --counts --analyze --locks]`

### Rate-Limiting

* Player commands: cooldown 2–5s.
* Admin diag: token bucket (cap 3–5, refill 0.2–0.5/s).

### Error Catalogue → i18n Keys

| Code                       | i18n key                         |
| -------------------------- | -------------------------------- |
| INSUFFICIENT\_FUNDS        | mincore.err.economy.insufficient |
| INVALID\_AMOUNT            | mincore.err.economy.invalid      |
| UNKNOWN\_PLAYER            | mincore.err.player.unknown       |
| IDEMPOTENCY\_REPLAY        | mincore.err.idem.replay          |
| IDEMPOTENCY\_MISMATCH      | mincore.err.idem.mismatch        |
| DEADLOCK\_RETRY\_EXHAUSTED | mincore.err.db.deadlock          |
| CONNECTION\_LOST           | mincore.err.db.unavailable       |
| DEGRADED\_MODE             | mincore.err.db.degraded          |
| MIGRATION\_LOCKED          | mincore.err.migrate.locked       |
| NAME\_AMBIGUOUS            | mincore.err.player.ambiguous     |
| INVALID\_TZ                | mincore.err.tz.invalid           |
| OVERRIDES\_DISABLED        | mincore.err.tz.overridesDisabled |

---

## 5) Developer Guide & Tooling

### Toolchain & Layout

Java 21; Gradle 8.1.4+; Loom 1.11.x; MariaDB driver 3.4.x.
Artifacts: `mincore` (core) + `mincore-jdbc` (driver shim); ship as bundle JAR.
Paths: config `config/mincore.json5`, backups `./backups/mincore`, optional logs `logs/mincore.jsonl`.

### Local MariaDB (Docker)

```bash
docker run --name mincore-mariadb -p 3306:3306 -e MARIADB_ROOT_PASSWORD=devroot -d mariadb:11
```

```sql
CREATE DATABASE IF NOT EXISTS mincore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mincore'@'%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON mincore.* TO 'mincore'@'%';
FLUSH PRIVILEGES;
```

### Build & Run

`./gradlew clean build` → JAR(s); `./gradlew runServer` for dev. Minimal config from Part 3.

### One-Shot Smoke Test

1. `/mincore db ping|info`
2. Two players; deposit; transfer with idemKey; repeat transfer (expect `IDEMPOTENCY_REPLAY` no-op).
3. `/mincore ledger recent` shows entries; events fired.
4. `/mincore export --all --out ./backups/mincore --gzip true`
5. Cleanup; `/mincore doctor --counts --fk`

### Testing Strategy

* Unit: normalization, error-code mapping, rate-limit logic.
* Integration: real MariaDB; contention; idempotency replay/mismatch; retention sweep.
* CI: DB service; env secrets; artifacts (e.g., backups).

### Style, Exceptions, JavaDoc

* Spotless + Google Java Style; zero warnings.
* Map exceptions to codes; no raw SQL to public API.
* JavaDoc all public APIs; document invariants and usage.

### Localization Workflow (i18n)

* Keys in `assets/mincore/lang/*.json` (e.g., `en_us.json`).
* Consistent naming and placeholders.
* Validation task for missing/mismatched keys.
* Translator notes in Markdown.

### Release Engineering

* SemVer intent (API+schema).
* Publish DDL + commented config example per release.
* Pre-release: tests green; smoke test; migrate up (empty & non-empty DB); export+restore verification.

### Backward-Compat & Deprecations

* `@Deprecated` + JavaDoc + rate-limited warnings; keep ≥1 minor.
* Schema changes additive in minors; destructive only in majors.
* Transitional config flags for behavior changes when needed.

### Example Add-On (“Hello Ledger”)

Listen for `PlayerRegisteredEvent`; deposit 100 units with idemKey; i18n message. Shows idempotency, i18n, TZ, event handling.

### Contributor Workflow

* Branches (`development`), small PRs, Conventional Commits, PR template, ownership for sensitive areas, no secrets in repo, TLS guidance.

### Ops-Grade Smoke Test (Extended)

* `/mincore migrate --apply`, `/mincore db info`
* Players + deposit/transfer/replay
* Export verify; cleanup; `/mincore doctor --counts --fk`

### Deliverables (“Done”)

* `CONTRIBUTING.md`, style guide, example add-on, smoke test script, updated DDL/config example per release.

---

## 6) Agent Checklists

### 6.1 Implementation Checklist

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

### 6.2 Ops Checklist

* [ ] DB user least-priv; TLS if cross-host.
* [ ] `session.forceUtc=true`; backups enabled 04:45 UTC; retention configured.
* [ ] `/mincore db ping|info` OK; `/mincore diag` OK.
* [ ] `jobs list` shows backup & cleanup; advisory locks tested.
* [ ] Export and restore tested; checksums verified.
* [ ] Logs monitored for `CONNECTION_LOST`, `IDEMPOTENCY_*`, deadlocks.

### 6.3 Add-on Checklist

* [ ] Use idempotent wallet APIs for networked flows.
* [ ] Subscribe to events; dedupe by (player, seq).
* [ ] Use `ExtensionDatabase.tryAdvisoryLock` for migrations/jobs.
* [ ] Localize user messages; use server/player TZ render helpers.
* [ ] Avoid blocking main thread; schedule to async when needed.

---

## 7) Error Codes (Canonical Set)

`INSUFFICIENT_FUNDS`, `INVALID_AMOUNT`, `UNKNOWN_PLAYER`, `IDEMPOTENCY_REPLAY`, `IDEMPOTENCY_MISMATCH`, `DEADLOCK_RETRY_EXHAUSTED`, `CONNECTION_LOST`, `DEGRADED_MODE`, `MIGRATION_LOCKED`, `NAME_AMBIGUOUS`, `INVALID_TZ`, `OVERRIDES_DISABLED`.

**i18n mapping** in Section 4.6.

---

## 8) Command Reference (One-Page)

* `/timezone` help; `/timezone set <ZoneId>`
* `/mincore db ping|info`; `/mincore diag`
* `/mincore ledger recent [N] | player <name|UUID> [N] | addon <id> [N] | reason <substring> [N]`
* `/playtime me | top [N] | reset <player>`
* `/mincore jobs list | run <job>`; `/mincore backup now`
* Admin-only: `/mincore migrate --check|--apply` • `/mincore export --all [--out <dir>] [--gzip true]` • `/mincore restore --mode <fresh|merge> [--atomic|--staging] --from <dir>` • `/mincore doctor [--fk --orphans --counts --analyze --locks]`

**Rate-limits:** Player cmds cooldown 2–5s; admin diag token bucket (\~cap 3–5, refill 0.2–0.5/s).

---

## 9) Data Retention & Integrity Rules

* No auto-prune for `players` or `core_ledger`.
* `core_requests` pruned by job only; player/ledger data unaffected.
* All timestamps UTC seconds; store UUIDs as `BINARY(16)`; JSON values valid and ≤ \~8KB.
* Withdraw/transfer must never permit negative balances; use conditional updates or transactions.

---

## 10) Appendix: Minimal GRANT & Docker

See Sections 3.9 and 5.2 for SQL and Docker snippets.

---

**End of AGENTS.md — MinCore v0.2.0 (Unified & Consistent)**
