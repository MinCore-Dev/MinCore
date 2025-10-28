# MinCore Smoke Test (Ops-Grade)

The script and checklist below exercise the production guarantees described in the v1.0.0 master
spec. Run it against a staging server before upgrades and after significant configuration changes.

## Prerequisites

* Fabric server running MinCore with operator access (RCON recommended).
* MariaDB instance seeded with the MinCore schema.
* Two throwaway player accounts you can join with for verification.
* `mcrcon` (or any RCON CLI) installed locally.

Export the following environment variables if you want to drive the automated script in
`scripts/smoke-test.sh`:

```sh
export MINCORE_RCON_HOST=127.0.0.1
export MINCORE_RCON_PORT=25575
export MINCORE_RCON_PASSWORD=change-me
```

## Manual Checklist

1. **Database connectivity**
   * `/mincore db ping`
   * `/mincore db info`
   * `/mincore diag`
2. **Player bootstrap** *(requires `modules.playtime.enabled` = `true`; skip otherwise and confirm the flag before expecting the
   command responses — the automated script does not cover this step, so note the configuration state explicitly when you skip
   it)*
   * Join with Player A, run `/playtime me` (should show a few seconds).
   * Run `/mincore ledger recent 5` and confirm the ledger header prints *(skip if `modules.ledger.enabled` = `false` and log
     that the module is intentionally disabled before continuing).* 
3. **Wallet operations** *(requires `modules.ledger.enabled` = `true`; if the ledger module is disabled, skip these ledger checks
   and verify the configuration flag before expecting ledger output — the automated script will emit the same guidance)*
   * Run `wallet deposit` via console or the built-in wallet module APIs to give Player A 1,000 units (use an idempotent
     key).
   * Transfer 250 units from Player A to Player B twice with the same idempotency key – the second invocation should return an
     `IDEMPOTENCY_REPLAY` code and the ledger should not duplicate the transfer (if the ledger module is disabled, expect a
     no-op write with success feedback only).
4. **Scheduler verification** *(requires `modules.scheduler.enabled` = `true`; when disabled, skip these checks and confirm the
   configuration flag — `scripts/smoke-test.sh` emits the same reminder)*
   * `/mincore jobs list` – ensure `backup` and `cleanup.idempotencySweep` are present with sane
     next-run timestamps.
   * `/mincore jobs run backup` – confirm the job queues.
   * Inspect the backup directory for a new `mincore-*.jsonl.gz` and `.sha256` checksum.
5. **Backup + restore**
   * `/mincore backup now` – expect `mincore.cmd.backup.queued` feedback.
   * Use `/mincore export --all --out ./backups/mincore --gzip true`.
   * Copy the generated archive aside; test `/mincore restore --mode fresh --atomic --from <dir>` on a
     staging database.
6. **Doctor**
   * `/mincore doctor --counts --fk` – confirm reported counts match expectations and no FK issues.
7. **Timezone & I18n**
   * If overrides are enabled, run `/timezone set Europe/Berlin` as a player and ensure success.
   * Toggle `/timezone clock 12` and `/timezone clock 24` to confirm the clock format updates and feedback shows the zone abbreviation + UTC offset label.
   * Toggle JSON logging and ensure locale messages render correctly in your configured language.

Record the commands issued and their responses in your change log or pull request before marking the
run successful.
