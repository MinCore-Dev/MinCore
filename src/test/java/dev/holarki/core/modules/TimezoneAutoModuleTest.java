/* Holarki © 2025 — MIT */
package dev.holarki.core.modules;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.holarki.core.Config;
import dev.holarki.util.TimezoneAutoDetector;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class TimezoneAutoModuleTest {

  @Test
  void moduleStartTwiceTriggersSingleLookupPerJoin() throws Exception {
    Config config = autoDetectConfig();
    var services = mock(dev.holarki.core.Services.class);
    ModuleContext context =
        new ModuleContext(
            config,
            services,
            ledger -> {},
            moduleId -> moduleId.equals(TimezoneModule.ID),
            (moduleId, type, service) -> {},
            extension -> {});

    AtomicReference<TimezoneAutoModule.ServerJoinListener> joinHolder = new AtomicReference<>();
    TimezoneAutoModule module = new TimezoneAutoModule(joinHolder::set);
    TimezoneAutoDetector detector = mock(TimezoneAutoDetector.class);

    try (MockedStatic<TimezoneAutoDetector> factory = Mockito.mockStatic(TimezoneAutoDetector.class)) {
      factory.when(() -> TimezoneAutoDetector.create(config)).thenReturn(Optional.of(detector));

      module.start(context);
      module.stop(context);
      module.start(context);

      UUID uuid = UUID.randomUUID();
      String ip = "203.0.113.5";

      TimezoneAutoModule.ServerJoinListener listener = joinHolder.get();
      assertNotNull(listener, "join listener should be registered");
      listener.onJoin(uuid, ip);

      verify(detector, times(1)).scheduleDetect(services, uuid, ip);
      module.stop(context);
    }
  }

  private static Config autoDetectConfig() throws Exception {
    Config.Db db =
        new Config.Db(
            "127.0.0.1",
            3306,
            "holarki",
            "holarki",
            "change-me",
            false,
            true,
            new Config.Pool(4, 1, 10_000L, 60_000L, 120_000L, 1));

    Config.Runtime runtime = new Config.Runtime(5);
    Config.Time time = new Config.Time(new Config.Display(ZoneId.of("UTC"), false, true));
    Config.I18n i18n =
        new Config.I18n(
            Locale.forLanguageTag("en-US"), List.of("en_US"), Locale.forLanguageTag("en-US"));
    Config.Ledger ledger =
        new Config.Ledger(true, 0, new Config.JsonlMirror(false, Path.of("logs/holarki-ledger.jsonl").toString()));
    Config.Backup backup =
        new Config.Backup(
            true,
            "0 0 0 * * *",
            "build/backups",
            Config.OnMissed.RUN_AT_NEXT_STARTUP,
            false,
            new Config.Prune(1, 1));
    Config.Cleanup cleanup =
        new Config.Cleanup(new Config.IdempotencySweep(true, "0 0 0 * * *", 30, 100));
    Config.Jobs jobs = new Config.Jobs(backup, cleanup);
    Config.SchedulerModule schedulerModule = new Config.SchedulerModule(true, jobs);
    Config.TimezoneModule timezoneModule =
        new Config.TimezoneModule(true, new Config.AutoDetect(true, "config/holarki.geoip.mmdb"));
    Config.Modules modules = new Config.Modules(ledger, schedulerModule, timezoneModule);
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
    return ctor.newInstance(db, runtime, time, i18n, modules, log);
  }
}

