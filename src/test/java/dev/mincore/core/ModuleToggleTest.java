/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.MinCoreApi;
import dev.mincore.core.Services;
import dev.mincore.core.modules.LedgerModule;
import dev.mincore.core.modules.ModuleManager;
import dev.mincore.core.modules.PlaytimeModule;
import dev.mincore.core.modules.SchedulerModule;
import dev.mincore.core.modules.TimezoneAutoModule;
import dev.mincore.core.modules.TimezoneModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration checks that each module can be toggled independently. */
final class ModuleToggleTest {
  private static final String DB_NAME = "module_toggle";

  @BeforeEach
  void beforeEach() throws Exception {
    MariaDbTestSupport.ensureDatabase(DB_NAME);
    resetApi();
  }

  @AfterEach
  void afterEach() throws Exception {
    MariaDbTestSupport.dropDatabase(DB_NAME);
    resetApi();
  }

  @Test
  void bootAllModules() throws Exception {
    Config config = baseConfig();
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      Set<String> active = harness.manager.activeModules();
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(LedgerModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(PlaytimeModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(SchedulerModule.ID));
    }
  }

  @Test
  void bootWithoutLedger() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            new Config.Ledger(false, modules.ledger().retentionDays(), modules.ledger().jsonlMirror()),
            modules.scheduler(),
            modules.timezone(),
            modules.playtime()));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(LedgerModule.ID));
      org.junit.jupiter.api.Assertions.assertNull(MinCoreApi.ledger());
    }
  }

  @Test
  void bootWithoutScheduler() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            modules.ledger(),
            new Config.SchedulerModule(false, modules.scheduler().jobs()),
            modules.timezone(),
            modules.playtime()));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(SchedulerModule.ID));
    }
  }

  @Test
  void bootWithoutTimezone() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            modules.ledger(),
            modules.scheduler(),
            new Config.TimezoneModule(false, modules.timezone().autoDetect()),
            modules.playtime()));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(TimezoneAutoModule.ID));
    }
  }

  @Test
  void bootWithoutPlaytime() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            modules.ledger(),
            modules.scheduler(),
            modules.timezone(),
            new Config.PlaytimeModule(false)));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(PlaytimeModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(MinCoreApi.playtime().isEmpty());
    }
  }

  @Test
  void bootWithTimezoneAutoDetectButMissingDatabase() throws Exception {
    Config config = withModules(
        withTime(baseConfig(),
            new Config.Time(new Config.Display(ZoneId.of("UTC"), false, true))),
        modules ->
            new Config.Modules(
                modules.ledger(),
                modules.scheduler(),
                new Config.TimezoneModule(true, new Config.AutoDetect(true, Path.of("missing.mmdb").toString())),
                modules.playtime()));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertTrue(harness.manager.isActive(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(harness.manager.isActive(TimezoneAutoModule.ID));
    }
  }

  private static Config baseConfig() throws Exception {
    return TestConfigFactory.create(DB_NAME, Path.of("build/test-backups"));
  }

  private static Set<String> allModules(Config config) {
    Set<String> requested = new LinkedHashSet<>();
    if (config.modules().ledger().enabled()) {
      requested.add(LedgerModule.ID);
    }
    if (config.modules().timezone().enabled()) {
      requested.add(TimezoneModule.ID);
    }
    if (config.modules().timezone().autoDetect().enabled() && config.time().display().autoDetect()) {
      requested.add(TimezoneAutoModule.ID);
    }
    if (config.modules().playtime().enabled()) {
      requested.add(PlaytimeModule.ID);
    }
    if (config.modules().scheduler().enabled()) {
      requested.add(SchedulerModule.ID);
    }
    return requested;
  }

  private static Config withModules(Config base, java.util.function.Function<Config.Modules, Config.Modules> fn)
      throws Exception {
    Config.Modules modules = fn.apply(base.modules());
    return rebuild(base, modules, base.time());
  }

  private static Config withTime(Config base, Config.Time time) throws Exception {
    return rebuild(base, base.modules(), time);
  }

  private static Config rebuild(Config base, Config.Modules modules, Config.Time time) throws Exception {
    Constructor<Config> ctor =
        Config.class.getDeclaredConstructor(
            Config.Db.class,
            Config.Runtime.class,
            Config.Time.class,
            Config.I18n.class,
            Config.Modules.class,
            Config.Log.class);
    ctor.setAccessible(true);
    return ctor.newInstance(base.db(), base.runtime(), time, base.i18n(), modules, base.log());
  }

  private static void resetApi() throws Exception {
    Field services = MinCoreApi.class.getDeclaredField("services");
    services.setAccessible(true);
    services.set(null, null);
    Field ledger = MinCoreApi.class.getDeclaredField("ledger");
    ledger.setAccessible(true);
    ledger.set(null, null);
  }

  private static final class TestHarness implements AutoCloseable {
    private final Services services;
    private final ModuleManager manager;

    TestHarness(Config config) {
      this.services = CoreServices.start(config);
      this.manager = new ModuleManager(config, services);
      MinCoreApi.bootstrap(services);
    }

    void start(Set<String> modules) {
      manager.start(modules);
    }

    @Override
    public void close() throws Exception {
      manager.close();
      services.shutdown();
    }
  }
}
