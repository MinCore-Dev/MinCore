/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link LocaleManager}. */
class LocaleManagerTest {

  @AfterEach
  void tearDown() {
    LocaleManager.resetForTests();
  }

  @Test
  void defaultConfigLoadsRealBundle() throws Exception {
    Config config = configWithLocales(List.of("en_US"), locale("en-US"), locale("en-US"));

    assertDoesNotThrow(() -> LocaleManager.initialize(config));

    assertFalse(LocaleManager.translations(locale("en-US")).isEmpty());
    assertEquals(
        "Database OK (ping %s ms)",
        LocaleManager.translate("holarki.cmd.db.ping.ok", locale("en-US")));
  }

  @Test
  void unknownLocaleFallsBackToDefault() throws Exception {
    Config config = configWithLocales(List.of("en_US"), locale("en-US"), locale("en-US"));

    LocaleManager.initialize(config);

    Locale requested = locale("fr-FR");
    assertEquals(locale("en-US"), LocaleManager.resolveOrDefault(requested));
    assertEquals(
        "Database OK (ping %s ms)",
        LocaleManager.translate("holarki.cmd.db.ping.ok", requested));
  }

  @Test
  void missingTranslationKeyFallsBackToConfiguredFallback() throws Exception {
    Config config =
        configWithLocales(List.of("en_US", "zz_ZZ"), locale("en-US"), locale("zz-ZZ"));

    LocaleManager.initialize(config);

    String translated = LocaleManager.translate("holarki.test.onlyFallback", locale("en-US"));
    assertEquals("Fallback translation", translated);
  }

  @Test
  void missingLocaleFileFailsFast() throws Exception {
    Config config = configWithLocales(List.of("qq_QQ"), locale("qq-QQ"), locale("qq-QQ"));

    assertThrows(IllegalStateException.class, () -> LocaleManager.initialize(config));
  }

  private static Config configWithLocales(
      List<String> enabled, Locale defaultLocale, Locale fallbackLocale) throws Exception {
    Config.Db db =
        new Config.Db(
            "127.0.0.1",
            3306,
            "holarki",
            "user",
            "pass",
            false,
            true,
            new Config.Pool(1, 0, 1_000L, 10_000L, 30_000L, 1));

    Config.Runtime runtime = new Config.Runtime(5);
    Config.Time time = new Config.Time(new Config.Display(ZoneId.of("UTC"), false, false));
    Config.I18n i18n = new Config.I18n(defaultLocale, enabled, fallbackLocale);
    Config.Ledger ledger = new Config.Ledger(false, 0, new Config.JsonlMirror(false, ""));
    Config.Backup backup =
        new Config.Backup(false, "0 0 0 * * *", "./backups", Config.OnMissed.SKIP, false,
            new Config.Prune(1, 1));
    Config.Cleanup cleanup =
        new Config.Cleanup(new Config.IdempotencySweep(false, "0 0 0 * * *", 1, 1));
    Config.Jobs jobs = new Config.Jobs(backup, cleanup);
    Config.SchedulerModule scheduler = new Config.SchedulerModule(false, jobs);
    Config.TimezoneModule timezone = new Config.TimezoneModule(false, new Config.AutoDetect(false, ""));
    Config.Modules modules = new Config.Modules(ledger, scheduler, timezone);
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

  private static Locale locale(String tag) {
    return Locale.forLanguageTag(tag);
  }
}
