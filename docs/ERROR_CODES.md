# MinCore Error Code Catalogue

MinCore surfaces canonical `ErrorCode` values across wallet operations, admin commands, and logs.
Use this table to map failures to localisation keys and recommended operator actions.

| Code | i18n Key | Typical Source | Operator Guidance |
| ---- | -------- | -------------- | ----------------- |
| `INSUFFICIENT_FUNDS` | `mincore.err.economy.insufficient` | Withdraw/transfer attempts | Inform players their balance is too low; review ledger entries for suspicious drains. |
| `INVALID_AMOUNT` | `mincore.err.economy.invalid` | Negative/zero amount validation | Check the caller for bad arguments or overflow. |
| `UNKNOWN_PLAYER` | `mincore.err.player.unknown` | Missing UUID/name in players table | Ensure the target has joined the server; verify UUID casing. |
| `IDEMPOTENCY_REPLAY` | `mincore.err.idem.replay` | Wallet operation repeated with same payload | Safe no-op; investigate why the upstream retried. |
| `IDEMPOTENCY_MISMATCH` | `mincore.err.idem.mismatch` | Reused idempotency key with different payload | Treat as poisoned; audit the caller for duplicate keys. |
| `DEADLOCK_RETRY_EXHAUSTED` | `mincore.err.db.deadlock` | withRetry exhausted for SQL deadlock/timeout | Re-run the command once load subsides; inspect DB metrics. |
| `CONNECTION_LOST` | `mincore.err.db.unavailable` | Hikari unable to reach MariaDB | Check database availability, credentials, and TLS configuration. |
| `DEGRADED_MODE` | `mincore.err.db.degraded` | Core refusing writes while reconnecting | Resolve database outage; writes will resume once connectivity returns. |
| `MIGRATION_LOCKED` | `mincore.err.migrate.locked` | Migrations held by another node | Wait for the other process or clear the advisory lock manually. |
| `NAME_AMBIGUOUS` | `mincore.err.player.ambiguous` | `/mincore ledger player <name>` duplicate matches | Use the UUID to disambiguate players with similar names. |
| `INVALID_TZ` | `mincore.err.tz.invalid` | `/timezone set` with bad zone ID | Provide a valid [IANA Zone ID](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones). |
| `OVERRIDES_DISABLED` | `mincore.err.tz.overridesDisabled` | Overrides disabled in config | Update `core.time.display.allowPlayerOverride` or advise players it’s locked. |

All error codes are defined in [`src/main/java/dev/mincore/api/ErrorCode.java`](../src/main/java/dev/mincore/api/ErrorCode.java) and logged with the `(mincore)` prefix for easy filtering.
