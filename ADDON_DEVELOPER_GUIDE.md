
# MinCore Add-On Developer Guide (v1.0.0)

Audience: Java developers building server-side Fabric add-ons that depend on MinCore.  
Goal: Give you everything you need to build against MinCore correctly on the first try, with clear contracts, examples, and patterns.

> Source of truth is MinCore Master Spec v1.0.0. This guide restates the key parts for add-on authors and adds hands-on examples.

---

## Table of Contents

1. What MinCore Provides
2. Compatibility and Requirements
3. Project Scaffold
   - Gradle build.gradle
   - fabric.mod.json
   - Running a Dev Server
4. Core Concepts and Contracts
   - Threading, Ordering, Events
   - Idempotency, Reasons, Amounts
   - UTC Storage, Timezone Rendering, i18n
5. Public API Overview
   - MinCoreApi entry points
   - Players API
   - Wallets API
   - Attributes API
   - Ledger API
   - Playtime API
   - Events API
   - ExtensionDatabase API
6. Database Usage for Add-ons
   - Connecting and Retrying
   - Advisory Locks and Singletons
   - Idempotent DDL and Migrations
   - Example Tables and Queries
7. Scheduling Work
8. Examples
   - Hello Ledger
   - Daily Login Bonus with Idempotency
   - Custom Table and Leaderboard
9. User-Facing Commands in Your Add-on
10. Localization and Messages
11. Error Codes and i18n Keys
12. Testing and CI
13. Operational Tips
14. Do and Do Not
15. FAQ
16. Checklist Before Release

---

## What MinCore Provides

MinCore is a small core that gives you production-grade primitives so you can build features faster and safer. As of the bundle-everything release, every first-party module ships inside the single MinCore jar—server owners toggle optional pieces via configuration instead of mixing and matching separate add-on jars.

- Database access using a managed HikariCP pool, with ExtensionDatabase to borrow connections and do safe retries.
- Wallets API with deposit, withdraw, and transfer. Built-in idempotency keys and durable ledger that can be disabled per server.
- Events that fire after commit on background threads. At least once delivery. Per player ordering.
- Scheduler and jobs for backups and cleanup. You can also schedule your own tasks using the provided executor patterns.
- Player playtime tracking in memory, with query and reset helpers.
- i18n and timezone aware rendering across MinCore commands. You can add your own localization for your add-on.

MinCore does not implement gameplay rules. You implement those in your add-on using these core services.

Previous releases sometimes shipped optional first-party add-ons (for example, a ledger writer) as separate jars. Those have been folded into the bundled modules listed above. If you encounter documentation or code referring to "packaged add-ons", treat them as historical—server owners now rely on the configuration toggles described below.

## Compatibility and Requirements

- Minecraft Fabric 1.21.8 server
- Java 21 LTS
- Fabric Loom 1.11.x for development builds
- MariaDB 10.6 or newer recommended. MySQL 8.0 or newer supported
- Character set utf8mb4 and InnoDB tables
- MinCore jar available on the server and on your dev classpath

## Built-in modules and server toggles

MinCore exposes module-level switches in `config/mincore.json5`. Server owners manage features by setting these flags; make sure your add-on handles the disabled states gracefully.

| Module | Default | Toggle(s) | Developer guidance |
| ------ | ------- | --------- | ------------------ |
| Core runtime (DB, migrations, wallet engine, events) | Enabled | Always on | Assume these facilities exist. Failure implies a misconfigured server. |
| Ledger persistence | Enabled | `modules.ledger.enabled` (primary table); `modules.ledger.file.enabled` (JSONL mirror) | Ledger API calls are safe even when disabled—they become no-ops. Avoid assuming persistence for auditing unless you validate the setting or expose your own storage. |
| Backup exporter | Enabled | `modules.scheduler.enabled` plus `modules.scheduler.jobs.backup.enabled` | If you depend on scheduled exports (e.g., to read JSONL artefacts), document that requirement and probe job status via `/mincore jobs list`. |
| Idempotency sweep | Enabled | `modules.scheduler.jobs.cleanup.idempotencySweep.enabled` (requires `modules.scheduler.enabled`) | Disabling sweeps may grow the idempotency registry. Handle `IDEMPOTENCY_*` errors defensively. |
| Playtime tracker | Enabled | `modules.playtime.enabled` | Bundled into the core; disable only if your add-on can operate without `/playtime`. |
| Timezone services | Enabled | `modules.timezone.enabled`; GeoIP via `modules.timezone.autoDetect.enabled` | Respect the player override flag—APIs still exist but `/timezone set` may be unavailable. |
| i18n bundle | Enabled | `core.i18n.enabledLocales` | Only rely on locales listed in the config. Provide fallbacks if your add-on ships extra translations. |

External extensions: If you build companion mods that hook into MinCore, treat the bundled modules as shared infrastructure. Use the APIs provided (`MinCoreApi.wallets()`, `MinCoreApi.ledger()`, `MinCoreApi.events()`, etc.) and do not attempt to repack or shade them—everything you need is already on the classpath. When a module is disabled, the API will either be absent (never true for public entry points) or will no-op/log accordingly. Feature-detect by reading configuration (when you have file access) or by calling the API and checking for benign behaviour.

Packaging expectations: Publish only your own add-on jar. Do not redistribute the legacy MinCore add-on bundles; they no longer exist. Declare a dependency on the core (`"depends": { "mincore": ">=1.0.0" }`) and document any module-level requirements so server operators can keep those modules enabled.

## Project Scaffold

### Gradle build.gradle

Below is a minimal build file for a server-only Fabric add-on that depends on MinCore. If MinCore is not published to Maven for you, use a local file dependency.

```groovy
plugins {
  id 'java'
  id 'fabric-loom' version '1.11-SNAPSHOT'
}

group = 'dev.yourorg'
version = '1.0.0'
sourceCompatibility = JavaVersion.VERSION_21
targetCompatibility = JavaVersion.VERSION_21

repositories {
  mavenCentral()
  // If MinCore is in a private Maven, add that here
}

dependencies {
  minecraft "com.mojang:minecraft:1.21.8"
  mappings "net.fabricmc:yarn:1.21.8+build.1:v2"
  modImplementation "net.fabricmc:fabric-loader:0.17.2"
  modImplementation "net.fabricmc.fabric-api:fabric-api:0.133.4+1.21.8"

  // Choose one of the following based on how you obtain MinCore:
  // 1) Local file in your project
  // modImplementation files("libs/mincore-v1.0.0.jar")

  // 2) Or if published to Maven
  // modImplementation "dev.mincore:mincore:1.0.0"

  // You do not need to add the MariaDB driver to your add-on, the server owner provides it
}

tasks.withType(JavaCompile).configureEach {
  options.encoding = 'UTF-8'
}

loom {
  runs {
    server {
      server()
      name "Fabric Server Dev"
      programArgs "--nogui"
    }
  }
}
```

### fabric.mod.json

Declare a dependency on MinCore so your add-on loads after it.

```json
{
  "schemaVersion": 1,
  "id": "hello_addon",
  "version": "1.0.0",
  "name": "Hello Addon",
  "environment": "server",
  "entrypoints": {
    "main": [ "dev.yourorg.hello.HelloAddon" ]
  },
  "depends": {
    "fabricloader": ">=0.17.2",
    "minecraft": "1.21.8",
    "fabric-api": "*",
    "mincore": ">=1.0.0"
  }
}
```

### Running a Dev Server

```bash
./gradlew runServer
```
Place MinCore in run/mods during development if you use a local jar. Provide a local MariaDB as described in the main README. MinCore will create its schema on first start.

## Core Concepts and Contracts

### Threading, Ordering, Events

- All JDBC happens off the main thread inside MinCore. Your add-on must also avoid blocking the main thread.
- MinCore events are delivered on background threads after a successful commit. Delivery is at least once. Events for the same player are ordered.
- If your handler touches the world, hop to the main server thread. For example:

```java
// Example helper to hop to main thread
public static void onMain(MinecraftServer server, Runnable r) {
  server.execute(r);
}
```

### Idempotency, Reasons, Amounts

- Currency amounts are minor units stored in a Java long. For example, 1234 means 12.34 if your display is two decimals.
- Always provide a stable reason string. Keep it short and normalized, for example daily_login_bonus, shop_purchase, raffle_prize.
- Prefer idempotent wallet operations. Provide a deterministic idempotency key that encodes the operation. Example:
  - idem:daily_bonus:<playerUuid>:<yyyyMMdd>
  - idem:shop:order:<orderId>
  - idem:admin:grant:<ticketId>
- If you repeat the same idempotent call with the same logical payload, MinCore will no-op and return success without double charging or double paying.

### UTC Storage, Timezone Rendering, i18n

- All timestamps in storage are UTC seconds. Rendering for players should respect their timezone.
- MinCore commands render timestamps using each player’s timezone and clock preference (with zone abbreviations + UTC offsets) when `/timezone` is enabled.
- For your own messages, render times in a consistent zone. If you cannot query a player’s preference, use the server default or UTC and label it clearly.

## Public API Overview

The entry point is MinCoreApi. You get strongly typed service interfaces for common tasks.

### MinCoreApi entry points

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

Call these after MinCore is loaded. In Fabric, your onInitialize runs after dependencies, so it is safe to resolve the services in your entrypoint.

### Players API

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

Usage notes
- byName may be ambiguous. Use byNameAll to handle multiple matches and prompt the admin for a UUID.
- upsertSeen lets you seed or update player rows if your add-on manages external identities.
- balanceUnits queries the current wallet balance managed by MinCore.

### Wallets API

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

Usage notes
- Always validate amount > 0. Negative values are invalid.
- Prefer the idempotent overloads with idemKey. They protect you from retries, disconnects, and duplicate request delivery.
- transfer is atomic and never creates negative balances. If funds are insufficient, it fails cleanly.
- Each successful operation writes to the ledger with your reason and idempotency info.

### Attributes API

```java
public interface Attributes {
  Optional<String> get(UUID owner, String key);
  void put(UUID owner, String key, String jsonValue, long nowS);
  void remove(UUID owner, String key);
}
```

Usage notes
- Values are JSON strings. Keep individual values small, approximately 8 KB or less.
- Use this for per-player feature flags, settings, or cached state. For relational data, create your own tables.

### Ledger API

```java
public interface Ledger {
  void log(String addonId, String op, UUID from, UUID to, long amount,
           String reason, boolean ok, String code,
           String idemScope, String idemKey, String extraJson);
}
```

Usage notes
- Use this to record important non-wallet events. For example, a raffle draw result or a shop fulfillment step.
- addonId should be your mod id, for example hello_addon.
- code should be a short machine friendly outcome. Use the error catalogue when applicable.
- extraJson is a small JSON snippet with context. Keep it short.

To read ledger data, borrow a DB connection and query the core_ledger table directly. See Database Usage.

### Playtime API

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

Usage notes
- MinCore calls onJoin and onQuit internally. Your add-on usually only reads seconds, top, or resets a player.
- Display helper human is convenient for formatting durations.

### Events API

Event payloads are Java records. For example

```java
record BalanceChangedEvent(UUID player, long seq, long oldUnits, long newUnits, String reason, int version) {}
record PlayerRegisteredEvent(UUID player, long seq, String name, int version) {}
record PlayerSeenUpdatedEvent(UUID player, long seq, String oldName, String newName, long seenAtS, int version) {}
```

Delivery contract
- Fired after commit, on background threads.
- At least once delivery. Per player ordering is guaranteed.
- Your handler must be idempotent. Deduplicate if needed using your own keys.
- Hop to main thread before touching the world.

Subscription
- Use the subscription mechanism exposed by CoreEvents. The exact method name may be subscribe or similar. Consult the generated JavaDoc of your MinCore build.
- Treat handlers as background threads. Use server.execute when you need main thread work.

### ExtensionDatabase API

```java
public interface ExtensionDatabase {
  java.sql.Connection borrowConnection() throws java.sql.SQLException;
  boolean tryAdvisoryLock(String name);
  void releaseAdvisoryLock(String name);
  <T> T withRetry(SQLSupplier<T> action) throws java.sql.SQLException;
}
```

Usage notes
- Use borrowConnection for read and write. Always use prepared statements. Close resources promptly with try-with-resources.
- Use withRetry around small transactional work that is safe to retry on deadlocks and timeouts.
- Use tryAdvisoryLock for singleton behaviors across a cluster. Always release the lock in a finally block.

## Database Usage for Add-ons

### Connecting and Retrying

```java
var db = MinCoreApi.database();
try (var conn = db.borrowConnection()) {
  try (var ps = conn.prepareStatement("select name, balance_units from players where uuid=?")) {
    ps.setBytes(1, uuidToBytes(playerUuid));
    try (var rs = ps.executeQuery()) {
      // read data
    }
  }
}
```

Retry pattern

```java
var result = MinCoreApi.database().withRetry(() -> {
  try (var conn = MinCoreApi.database().borrowConnection()) {
    conn.setAutoCommit(false);
    // do work
    conn.commit();
    return true;
  }
});
```

### Advisory Locks and Singletons

Ensure only one node runs a job at a time

```java
var db = MinCoreApi.database();
if (db.tryAdvisoryLock("hello_addon:daily_job")) {
  try {
    // run job
  } finally {
    db.releaseAdvisoryLock("hello_addon:daily_job");
  }
}
```

Lock naming tips
- Prefix with your mod id.
- Keep names short and stable.
- Use different names for different jobs and migrations.

### Idempotent DDL and Migrations

Run your migrations at startup. Use advisory locks and CREATE IF NOT EXISTS

```java
var db = MinCoreApi.database();
if (db.tryAdvisoryLock("hello_addon:migrate:v1")) {
  try (var conn = db.borrowConnection(); var st = conn.createStatement()) {
    st.executeUpdate(
      "create table if not exists hello_wallet_bonus (" +
      "  player_uuid binary(16) not null," +
      "  bonus_day date not null," +
      "  primary key(player_uuid, bonus_day)" +
      ") engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci"
    );
    st.executeUpdate(
      "create index if not exists idx_hello_bonus_day on hello_wallet_bonus(bonus_day)"
    );
  } finally {
    db.releaseAdvisoryLock("hello_addon:migrate:v1");
  }
}
```

Notes
- Use binary(16) for UUIDs. Store as bytes. Convert with helper methods in your code.
- Add indexes that match your queries.
- For ALTERs, check for existing columns first by querying information_schema to keep migrations idempotent.

### Example Tables and Queries

Read recent ledger entries for your addon

```java
try (var conn = MinCoreApi.database().borrowConnection();
     var ps = conn.prepareStatement(
       "select ts_s, op, from_uuid, to_uuid, amount, reason, ok, code, extra_json " +
       "from core_ledger where addon_id = ? order by ts_s desc limit ?")) {
  ps.setString(1, "hello_addon");
  ps.setInt(2, 50);
  try (var rs = ps.executeQuery()) {
    while (rs.next()) {
      // process
    }
  }
}
```

## Scheduling Work

MinCore runs its own internal jobs. For your add-on, use Java executors or Fabric tick events. If you need singleton behavior across nodes, wrap your work in an advisory lock.

Example, run hourly with jitter

```java
var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
long initialDelay = java.util.concurrent.ThreadLocalRandom.current().nextLong(60, 180);
scheduler.scheduleAtFixedRate(() -> {
  if (MinCoreApi.database().tryAdvisoryLock("hello_addon:hourly")) {
    try {
      // do work
    } finally {
      MinCoreApi.database().releaseAdvisoryLock("hello_addon:hourly");
    }
  }
}, initialDelay, 3600, java.util.concurrent.TimeUnit.SECONDS);
```

## Examples

### Hello Ledger

Deposit 100 units the first time we see a player and log it

```java
public class HelloAddon implements net.fabricmc.api.ModInitializer {
  @Override public void onInitialize() {
    // On player join using Fabric
    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
      var uuid = handler.player.getUuid();
      var idem = "idem:hello:first_join:" + uuid;
      var ok = MinCoreApi.wallets().deposit(uuid, 100, "welcome_bonus", idem);
      if (ok) {
        MinCoreApi.ledger().log(
          "hello_addon", "bonus", null, uuid, 100,
          "welcome_bonus", true, "OK",
          "welcome", idem, "{}"
        );
        server.execute(() -> handler.player.sendMessage(
          net.minecraft.text.Text.literal("Welcome bonus: 100 units"), false));
      }
    });
  }
}
```

### Daily Login Bonus with Idempotency

Grant 50 units once per UTC day

```java
void grantDailyBonus(MinecraftServer server, UUID player) {
  var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
  var idem = "idem:daily_bonus:" + player + ":" + today;
  var ok = MinCoreApi.wallets().deposit(player, 50, "daily_login_bonus", idem);
  if (ok) {
    MinCoreApi.ledger().log("hello_addon", "daily_bonus", null, player, 50,
      "daily_login_bonus", true, "OK", "daily", idem, "{\"day\":\"" + today + "\"}");
  }
}
```

Hook this to a join event or a once-per-day scheduled sweep of online players.

### Custom Table and Leaderboard

Track a lightweight score while using MinCore for players and DB

```java
void upsertScore(UUID player, int delta) throws Exception {
  MinCoreApi.database().withRetry(() -> {
    try (var conn = MinCoreApi.database().borrowConnection()) {
      conn.setAutoCommit(false);
      try (var ps = conn.prepareStatement(
            "insert into hello_scores(player_uuid, score) values(?,?) " +
            "on duplicate key update score = score + values(score)")) {
        ps.setBytes(1, uuidToBytes(player));
        ps.setInt(2, delta);
        ps.executeUpdate();
      }
      conn.commit();
      return null;
    }
  });
}
```

## User-Facing Commands in Your Add-on

Provide simple commands that call MinCore. Example, /hello balance <player>
- Resolve player by name with Players.byNameAll
- If multiple matches, show a short list with UUIDs
- Then call Wallets.getBalance and render using your i18n

MinCore now ships a LuckPerms-first permission gateway under `dev.mincore.perms.Perms`. Use it at
the top of every command handler instead of depending on Fabric Permissions yourself.

```java
if (!Perms.check(player, "youraddon.command.balance", 3)) {
  player.sendMessage(Text.translatable("youraddon.err.permission"));
  return 0;
}
```

- Run checks on the server thread so LuckPerms user data is loaded.
- Pick sensible vanilla fallbacks: level `4` for admin-only flows, `3` for high-trust staff, `2`
  for moderators, `0` for everyone.
- When running async logic, switch back to the main thread before calling
  `Perms.checkUUID(server, uuid, node, level)`.

## Localization and Messages

- Put your locales in assets/<your_mod_id>/lang/en_us.json
- Keep keys stable and messages short. Prefer server timezone or explicit UTC labels for times
- When you surface MinCore errors, map them to user friendly messages in your own locale files

## Error Codes and i18n Keys

MinCore error catalogue, reference these in your logs or user messages when applicable

| Code | i18n key |
|---|---|
| INSUFFICIENT_FUNDS | mincore.err.economy.insufficient |
| INVALID_AMOUNT | mincore.err.economy.invalid |
| UNKNOWN_PLAYER | mincore.err.player.unknown |
| IDEMPOTENCY_REPLAY | mincore.err.idem.replay |
| IDEMPOTENCY_MISMATCH | mincore.err.idem.mismatch |
| DEADLOCK_RETRY_EXHAUSTED | mincore.err.db.deadlock |
| CONNECTION_LOST | mincore.err.db.unavailable |
| DEGRADED_MODE | mincore.err.db.degraded |
| MIGRATION_LOCKED | mincore.err.migrate.locked |
| NAME_AMBIGUOUS | mincore.err.player.ambiguous |
| INVALID_TZ | mincore.err.tz.invalid |
| OVERRIDES_DISABLED | mincore.err.tz.overridesDisabled |

## Testing and CI

- Unit test your idempotency key builders and reason normalization
- Integration test against a real MariaDB, use Docker in CI
- Simulate contention. Use withRetry to verify your code survives common deadlocks
- Spin up a dev server with your mod and MinCore. Exercise wallet and ledger flows. Verify ledger lines for every operation you perform

## Operational Tips

- Never block the main thread with JDBC or heavy work
- Always use prepared statements. Avoid dynamic SQL string concatenation
- Use advisory locks for singleton jobs and migrations
- Use idempotent keys for all wallet operations that may be retried
- Keep JSON snippets small in attributes and ledger extraJson
- Monitor backup success and database health through server logs and any metrics you have

## Do and Do Not

Do
- Use MinCore services, not your own unmanaged connections, so pooling and UTC session rules apply
- Use binary(16) for UUIDs in your own tables
- Validate amounts and reasons. Log useful codes and context to the ledger
- Handle ambiguous names and unknown players gracefully

Do not
- Block the main server thread with database work
- Bypass idempotency on operations that can be retried
- Store large blobs in attributes. Use your own tables instead
- Assume exact once event delivery. Always deduplicate

## FAQ

Can I use SQLite for my add-on tables  
No. Stay consistent with MariaDB or MySQL, and reuse MinCore’s pool via ExtensionDatabase.

Can I read the ledger without SQL  
The write API is provided. For reads, query the core_ledger table using borrowConnection.

How do I render times in a player’s timezone
Reuse `Timezones.preferences` + `TimeDisplay.formatDateTime` if you want the same zone abbreviation + UTC offset + clock style output as core commands. Otherwise, keep server timezone for simplicity.

Can I schedule cron strings  
MinCore’s internal jobs use a cron parser. For your add-on, rely on executors and advisory locks, or build your own small parser if needed.

## Checklist Before Release

- [ ] Gradle builds and runs on Java 21
- [ ] Declares depends on mincore >= 1.0.0
- [ ] Migrations wrapped in advisory locks, idempotent DDL verified
- [ ] Wallet operations use idempotency keys
- [ ] Commands handle ambiguous names and missing players
- [ ] Database usage is off-thread with prepared statements
- [ ] Tests cover idempotency, retries, and happy paths
- [ ] README explains your add-on’s economy effects and commands
