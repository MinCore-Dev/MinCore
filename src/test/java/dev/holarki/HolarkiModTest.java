/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.holarki.core.Config;
import dev.holarki.core.Services;
import dev.holarki.core.modules.ModuleManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HolarkiModTest {

  @AfterEach
  void resetFactory() {
    HolarkiMod.resetModuleManagerFactory();
  }

  @Test
  void initializeWithServicesCleansUpOnMigrationFailure() throws Exception {
    Config config = mock(Config.class, RETURNS_DEEP_STUBS);
    Services services = mock(Services.class, RETURNS_DEEP_STUBS);
    ModuleManager moduleManager = mock(ModuleManager.class);

    HolarkiMod.setModuleManagerFactory((cfg, svc) -> moduleManager);

    SQLException failure = new SQLException("boom");
    // Simulate a failure while applying migrations by throwing during connection borrow.
    org.mockito.Mockito
        .when(services.database().borrowConnection())
        .thenThrow(failure);

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> HolarkiMod.initializeWithServices(config, services));

    assertEquals("Migration failed", thrown.getMessage());
    assertEquals(failure, thrown.getCause());
    verify(moduleManager).close();
    verify(services).shutdown();
  }
}
