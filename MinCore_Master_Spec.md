# MinCore Master Spec (v0.2.0) — Unified & Consistent

> This is the consolidated, internally consistent master specification for **MinCore v0.2.0**.  
> It resolves prior naming/terminology clashes (e.g., **backup vs export**, **db info vs db status**), aligns commands/APIs across all parts, and improves section flow.  
> Treat this as the **sole source of truth** unless you explicitly override it.

---

## Part 1 — Product, Scope & Compatibility

### 1.1 Mission

MinCore is a **small, opinionated core** for Fabric Minecraft servers that gives add-on authors production‑grade primitives—**DB access + schema evolution, wallets + ledger, events, scheduler, playtime, i18n, timezone rendering**—so they can build features faster with **fewer foot‑guns** and consistent operational behavior.

### 1.2 Non‑Goals

- Full gameplay mod (economy rules, shops, quests) — that belongs in add‑ons.
- Web UI / hosted panel.
- Cross‑DB abstraction beyond **MariaDB/MySQL**.
- Default PII collection (e.g., IPs). Optional, explicit, documented only.
- Replacement for heavy schedulers/message buses.
- Analytics pipeline (we provide a **ledger mirror** for external tooling).

### 1.3 Design Principles

1) **Small core, strong contracts** • 2) **Ops‑first defaults** • 3) **I18n + TZ everywhere** • 4) **Idempotent by design** • 5) **Predictable failure behavior** • 6) **Zero‑surprises migrations** • 7) **Observability > Guesswork**.

### 1.4 Value Proposition for Add‑on Authors

- **Database**: HikariCP pool + **SchemaHelper** for safe, idempotent DDL (ensure‑table/index/column/check).
- **Wallets API**: deposit/withdraw/transfer with **idempotency keys** + **ledger**.
- **Ledger**: DB audit + optional **JSONL mirror**; indexed queries.
- **Events**: post‑commit, background; **per‑player ordering**.
- **Scheduler**: cron‑like UTC jobs; built‑in **backup** with retention.
- **Playtime**: in‑memory tracker.
- **I18n + TZ rendering** helpers.

### 1.5 Roadmap Snapshot (v0.2.0 highlights)

- Commented JSON5 config; backups 04:45 UTC; least‑priv DB; **Config Template Writer**.
- Server TZ default, optional per‑player TZ; **/timezone**.
- Commands: `/mincore diag`, `/mincore db ping|info`, `/mincore ledger …`, `/playtime me|top|reset`, `/mincore jobs list|run`, `/mincore backup now`.
- Dev standards: JavaDoc, Spotless, error‑code catalogue, example add‑on, smoke test.

### 1.6 Compatibility Matrix

| Area | Min/Target | Notes |
|---|---|---|
| **Minecraft (Fabric)** | 1.20+ (Fabric API) | Match repo branch specifics. |
| **Java** | **21 LTS** | Primary toolchain. |
| **Fabric Loader/Loom** | Loader compat; **Loom 1.11.x** | Follow buildscript. |
| **Database** | **MariaDB 10.6+** rec., **MySQL 8.0+** supp. | `utf8mb4`, InnoDB, `BINARY(16)` UUIDs. |
| **OS/Arch** | Linux x86_64 (primary), ARM64 (supported) | Windows/macOS for dev. |
| **Time** | UTC storage; TZ‑aware rendering | No DST in storage. |

**UUIDs** stored as `BINARY(16)` for compact, fast indexes.

### 1.7 Versioning & Support

- **APIs** behave semantically (no breaking in patch; minimize in minor).
- **Migrations** are idempotent ensure‑ops; staged constraints.
- **Security fixes** may bypass deprecation timelines.

### 1.8 Glossary

Add‑on • Services • Wallets • Ledger • Idempotency • SchemaHelper • Scheduler/Job • Playtime • I18n • TZ Rendering • Config Template Writer.

---

## Part 2 — Architecture & Persistence (MariaDB‑First)

### 2.1 High‑Level Architecture

- **Services** container exposes `Players`, `Wallets`, `Attributes`, `CoreEvents`, `ExtensionDatabase`, `Playtime`, `Ledger`.
- **All DB I/O off‑thread**; **events post‑commit**; **per‑player ordered**.

### 2.2 Threading & Ordering

- Never block main thread with JDBC.
- Events: background threads, **at‑least‑once**, ordered per player; add‑ons must dedupe and hop to main thread to touch world.

### 2.3 Resilience

- **DEGRADED mode** when DB down (writes refuse; reconnect every `runtime.reconnectEveryS`).  
- **withRetry** for deadlocks/timeouts.  
- **Idem storm** logs are rate‑limited.

### 2.4 Schema (DDL excerpts; rationale)

**`core_schema_version`**, **`players`** (`uuid BINARY(16)`, `name`, `name_lower` generated, `balance_units`, `*_at_s` UTC seconds),  
**`player_attributes`** (JSON ≤ ~8KB; `JSON_VALID` + length CHECK),  
**`core_requests`** (idempotency: `key_hash`, `scope`, `payload_hash`, `ok`, `created_at_s`, `expires_at_s`),  
**`player_event_seq`** (per‑player seq).

**Ledger `core_ledger`** (append‑only): `ts_s`, `addon_id`, `op`, `from_uuid`, `to_uuid`, `amount`, `reason`, `ok`, `code`, `seq`, `idem_scope`, `idem_key_hash`, `old_units`, `new_units`, `server_node`, `extra_json`; indexed on time/addon/op/from/to/reason/seq.

### 2.5 Idempotency (exact‑once)

Canonical payload for hash: `scope | fromUUID | toUUID | amount | reasonNorm`.  
Flow: insert request → compare on conflict (detect **MISMATCH**) → guarded updates (no negative balance) → mark ok → commit → emit event.

### 2.6 Indexing Strategy

Purposeful indexes on lookups/time windows/idempotency expiry.

### 2.7 JSONL Ledger Mirror

`jsonl/v1` lines with fields: `ts, addon, op, from, to, amount, reason, ok, code, seq?, idemScope?, oldUnits?, newUnits?, extra?`.  
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

UTC storage; server display TZ; optional per‑player `/timezone set <ZoneId>` (if enabled); owner‑only auto‑detect (privacy note); no player “auto” command.

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
  public static dev.mincore.api.storage.ExtensionDatabase database();
  public static Ledger ledger();
}
```

```java
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
  void log(String addonId, String op, UUID from, UUID to, long amount,
           String reason, boolean ok, String code,
           String idemScope, String idemKey, String extraJson);
}
```

**ExtensionDatabase**

```java
public interface ExtensionDatabase {
  java.sql.Connection borrowConnection() throws java.sql.SQLException;
  boolean tryAdvisoryLock(String name);
  void releaseAdvisoryLock(String name);
  <T> T withRetry(SQLSupplier<T> action) throws java.sql.SQLException;
}
```

### 4.4 Commands

**/timezone**  
- `/timezone` — help (+ current setting; note that auto‑detect is owner‑controlled).  
- `/timezone set <ZoneId>` (or `/timezone <ZoneId>`) — set personal TZ if enabled.  
Errors: `mincore.err.tz.invalid`, `mincore.err.tz.overridesDisabled`; Success: `mincore.cmd.tz.set.ok`.

**/mincore db**  
- `/mincore db ping` — driver/server versions, RTT. (`mincore.cmd.db.ping.ok|fail`)
- `/mincore db info` — pool stats, URL host/port (masked), TLS, isolation. (`mincore.cmd.db.info.*`)
- `/mincore diag` — ping + schema version + advisory lock check. (`mincore.cmd.diag.ok|fail`)

**/mincore ledger**  
- `recent [N]`, `player <name|UUID> [N]`, `addon <id> [N]`, `reason <substring> [N]`.  
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
| OVERRIDES_DISABLED | `mincore.err.tz.overridesDisabled` |

### 4.7 Output Examples

- `/timezone` help, `/mincore db ping`, `/mincore ledger recent`, `/playtime top N` — localized; timestamps in viewer TZ.

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

### 5.10 Example Add‑On (“Hello Ledger”)

Sketch: listen for `PlayerRegisteredEvent`; deposit 100 units with idemKey; i18n message to player. Shows best practices (idempotency, i18n, TZ, event handling).

### 5.11 Contributor Workflow

Branches (`development`), small PRs, Conventional Commits, PR template, code ownership for sensitive parts, no secrets in repo, TLS guidance.

### 5.12 Ops‑Grade Smoke Test (Extended)

- `/mincore migrate --apply`, `/mincore db info`  
- 2 players, deposit/transfer + replay test  
- `/mincore export …` → verify files  
- Cleanup via restore fresh/atomic or truncation  
- `/mincore doctor --counts --fk`

### 5.13 Deliverables (“Done”)

- **CONTRIBUTING.md**, style guide, example add‑on, smoke test script, updated DDL/config example per release.

---

**End of MinCore v0.2.0 — Unified & Consistent Master Spec**
