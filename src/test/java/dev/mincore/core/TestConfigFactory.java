/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Builds minimal {@link Config} instances for integration tests. */
final class TestConfigFactory {
  private TestConfigFactory() {}

  static Config create(String dbName, Path backupDir) throws Exception {
    Config.Db dbConfig =
        new Config.Db(
            "127.0.0.1",
            MariaDbTestSupport.port(),
            dbName,
            MariaDbTestSupport.USER,
            MariaDbTestSupport.PASSWORD,
            false,
            true,
            new Config.Pool(4, 1, 10_000L, 60_000L, 120_000L, 1));

    Config.Runtime runtime = new Config.Runtime(5);
    Config.Time time = new Config.Time(new Config.Display(ZoneId.of("UTC"), false, false));
    Config.I18n i18n =
        new Config.I18n(
            Locale.forLanguageTag("en-US"), List.of("en_US"), Locale.forLanguageTag("en-US"));
    Config.Ledger ledger =
        new Config.Ledger(
            true, 0, new Config.JsonlMirror(false, backupDir.resolve("ledger.jsonl").toString()));
    Config.Backup backup =
        new Config.Backup(
            true,
            "0 45 4 * * *",
            backupDir.toString(),
            Config.OnMissed.RUN_AT_NEXT_STARTUP,
            false,
            new Config.Prune(30, 10));
    Config.Cleanup cleanup =
        new Config.Cleanup(new Config.IdempotencySweep(true, "0 30 4 * * *", 30, 5_000));
    Config.Jobs jobs = new Config.Jobs(backup, cleanup);
    Config.SchedulerModule schedulerModule = new Config.SchedulerModule(true, jobs);
    Config.TimezoneModule timezoneModule =
        new Config.TimezoneModule(true, new Config.AutoDetect(false, ""));
    Config.PlaytimeModule playtimeModule = new Config.PlaytimeModule(true);
    Config.Modules modules = new Config.Modules(ledger, schedulerModule, timezoneModule, playtimeModule);
    Config.Log log = new Config.Log(false, 250L, "INFO");

    Constructor<Config> ctor =
        Config.class.getDeclaredConstructor(
            Config.Db.class,
            Config.Runtime.class,
            Config.Time.class,
            Config.I18n.class,
            Config.Modules.class,
            Config.Log.class);
    ctor.setAccessible(true);
    return ctor.newInstance(dbConfig, runtime, time, i18n, modules, log);
  }
}
