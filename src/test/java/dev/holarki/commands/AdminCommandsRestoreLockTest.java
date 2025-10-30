/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.holarki.api.storage.ModuleDatabase;
import dev.holarki.core.Services;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.junit.jupiter.api.Test;

class AdminCommandsRestoreLockTest {

  @Test
  void cmdRestoreRejectsWhileLockHeld() throws Exception {
    Services services = mock(Services.class);
    ModuleDatabase database = mock(ModuleDatabase.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ServerCommandSource src = mock(ServerCommandSource.class);
    MinecraftServer server = mock(MinecraftServer.class);

    when(services.database()).thenReturn(database);
    when(services.scheduler()).thenReturn(scheduler);
    when(src.getServer()).thenReturn(server);
    when(database.tryAdvisoryLock("holarki_restore")).thenReturn(true, false);

    Method method =
        AdminCommands.class.getDeclaredMethod(
            "cmdRestore", ServerCommandSource.class, Services.class, String.class);
    method.setAccessible(true);

    int first = (int) method.invoke(null, src, services, "--mode fresh --from /tmp");
    assertEquals(1, first);
    verify(database).tryAdvisoryLock("holarki_restore");
    verify(scheduler).execute(any(Runnable.class));

    clearInvocations(database, scheduler, src);

    int second = (int) method.invoke(null, src, services, "--mode fresh --from /tmp");
    assertEquals(0, second);
    verify(database).tryAdvisoryLock("holarki_restore");
    verify(scheduler, never()).execute(any(Runnable.class));
  }
}
