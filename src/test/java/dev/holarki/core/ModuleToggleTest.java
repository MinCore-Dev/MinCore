/* Holarki © 2025 — MIT */
package dev.holarki.core;

import dev.holarki.api.HolarkiApi;
import dev.holarki.api.Ledger;
import dev.holarki.core.Services;
import dev.holarki.core.modules.HolarkiModule;
import dev.holarki.core.modules.LedgerModule;
import dev.holarki.core.modules.ModuleActivation;
import dev.holarki.core.modules.ModuleContext;
import dev.holarki.core.modules.ModuleManager;
import dev.holarki.core.modules.SchedulerModule;
import dev.holarki.core.modules.TimezoneAutoModule;
import dev.holarki.core.modules.TimezoneModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
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
  void moduleStartupFailureRollsBackResources() throws Exception {
    Config config = baseConfig();
    try (TestHarness harness = new TestHarness(config)) {
      FailingModule failing = new FailingModule();
      Field modulesField = ModuleManager.class.getDeclaredField("modules");
      modulesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, HolarkiModule> modules =
          (Map<String, HolarkiModule>) modulesField.get(harness.manager);
      modules.put(failing.id(), failing);

      RuntimeException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              RuntimeException.class, () -> harness.manager.start(Set.of(failing.id())));

      org.junit.jupiter.api.Assertions.assertEquals("Failed to start modules", error.getMessage());
      org.junit.jupiter.api.Assertions.assertTrue(failing.started);
      org.junit.jupiter.api.Assertions.assertTrue(failing.stopped);
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(failing.id()));
      org.junit.jupiter.api.Assertions.assertTrue(harness.manager.activeModules().isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(
          harness.manager.service(DummyService.class).isEmpty());
      org.junit.jupiter.api.Assertions.assertNull(HolarkiApi.ledger());
    }
  }

  @Test
  void bootAllModules() throws Exception {
    Config config = baseConfig();
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      Set<String> active = harness.manager.activeModules();
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(LedgerModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertTrue(active.contains(SchedulerModule.ID));
    }
  }

  @Test
  void ledgerDisabledKeepsHandleAndSkipsWrites() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            new Config.Ledger(false, modules.ledger().retentionDays(), modules.ledger().jsonlMirror()),
            modules.scheduler(),
            modules.timezone()));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(LedgerModule.ID));
      org.junit.jupiter.api.Assertions.assertFalse(
          harness.manager.activeModules().contains(LedgerModule.ID));
      var ledger = HolarkiApi.ledger();
      org.junit.jupiter.api.Assertions.assertNotNull(ledger);
      ledger.log("test", "noop", null, null, 1L, "disabled", true, null, null, null, null);
      boolean exists =
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
              () -> harness.services().database().schema().tableExists("core_ledger"));
      if (exists) {
        try (var conn = harness.services().database().borrowConnection();
            var ps = conn.prepareStatement("SELECT COUNT(*) FROM core_ledger");
            var rs = ps.executeQuery()) {
          org.junit.jupiter.api.Assertions.assertTrue(rs.next());
          org.junit.jupiter.api.Assertions.assertEquals(0L, rs.getLong(1));
        }
      } else {
        org.junit.jupiter.api.Assertions.assertFalse(exists);
      }
    }
  }

  @Test
  void bootWithoutScheduler() throws Exception {
    Config config = withModules(baseConfig(), modules ->
        new Config.Modules(
            modules.ledger(),
            new Config.SchedulerModule(false, modules.scheduler().jobs()),
            modules.timezone()));
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
            new Config.TimezoneModule(false, modules.timezone().autoDetect())));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(TimezoneAutoModule.ID));
    }
  }

  @Test
  void playtimeTrackerAlwaysAvailable() throws Exception {
    Config config = baseConfig();
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertNotNull(harness.services().playtime());
      org.junit.jupiter.api.Assertions.assertSame(
          harness.services().playtime(), HolarkiApi.playtime());
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
                new Config.TimezoneModule(true, new Config.AutoDetect(true, Path.of("missing.mmdb").toString()))));
    try (TestHarness harness = new TestHarness(config)) {
      harness.start(allModules(config));
      org.junit.jupiter.api.Assertions.assertTrue(harness.manager.isActive(TimezoneModule.ID));
      org.junit.jupiter.api.Assertions.assertFalse(harness.manager.isActive(TimezoneAutoModule.ID));
      org.junit.jupiter.api.Assertions.assertFalse(
          harness.manager.activeModules().contains(TimezoneAutoModule.ID));
    }
  }

  private static final class DummyService {}

  private static final class TestLedger implements Ledger {
    @Override
    public void log(
        String moduleId,
        String op,
        java.util.UUID from,
        java.util.UUID to,
        long amount,
        String reason,
        boolean ok,
        String code,
        String idemScope,
        String idemKey,
        String extraJson) {}
  }

  private static final class FailingModule implements HolarkiModule {
    private boolean started;
    private boolean stopped;

    @Override
    public String id() {
      return "test.fail";
    }

    @Override
    public ModuleActivation start(ModuleContext context) {
      started = true;
      context.publishLedger(new TestLedger());
      context.publishService(id(), DummyService.class, new DummyService());
      throw new IllegalStateException("boom");
    }

    @Override
    public void stop(ModuleContext context) {
      stopped = true;
      context.publishLedger(null);
      context.publishService(id(), DummyService.class, null);
    }
  }

  private static Config baseConfig() throws Exception {
    return TestConfigFactory.create(DB_NAME, Path.of("build/test-backups"));
  }

  private static Set<String> allModules(Config config) {
    Set<String> requested = new LinkedHashSet<>();
    requested.add(LedgerModule.ID);
    if (config.modules().timezone().enabled()) {
      requested.add(TimezoneModule.ID);
    }
    if (config.modules().timezone().autoDetect().enabled() && config.time().display().autoDetect()) {
      requested.add(TimezoneAutoModule.ID);
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
    Field services = HolarkiApi.class.getDeclaredField("services");
    services.setAccessible(true);
    services.set(null, null);
    Field ledger = HolarkiApi.class.getDeclaredField("ledger");
    ledger.setAccessible(true);
    ledger.set(null, null);
  }

  private static final class TestHarness implements AutoCloseable {
    private final Services services;
    private final ModuleManager manager;

    TestHarness(Config config) {
      this.services = CoreServices.start(config);
      this.manager = new ModuleManager(config, services);
      HolarkiApi.bootstrap(services);
    }

    void start(Set<String> modules) {
      manager.start(modules);
    }

    Services services() {
      return services;
    }

    @Override
    public void close() throws Exception {
      manager.close();
      services.shutdown();
    }
  }
}
