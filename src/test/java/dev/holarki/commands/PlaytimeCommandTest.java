/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.holarki.api.Playtime;
import dev.holarki.api.Players;
import dev.holarki.core.Services;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlaytimeCommandTest {

  @Test
  void cmdTopBatchesLookupsOffThread() throws Exception {
    Services services = mock(Services.class);
    Playtime playtime = mock(Playtime.class);
    Players players = mock(Players.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ServerCommandSource src = mock(ServerCommandSource.class);
    MinecraftServer server = mock(MinecraftServer.class);

    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    when(services.playtime()).thenReturn(playtime);
    when(services.players()).thenReturn(players);
    when(services.scheduler()).thenReturn(scheduler);
    when(playtime.top(10))
        .thenReturn(List.of(new Playtime.Entry(first, 120L), new Playtime.Entry(second, 60L)));
    when(src.getServer()).thenReturn(server);
    when(players.byUuidBulk(anyCollection()))
        .thenReturn(Map.of(first, playerRef(first, "Alpha"), second, playerRef(second, "Bravo")));

    Method method =
        PlaytimeCommand.class.getDeclaredMethod(
            "cmdTop", ServerCommandSource.class, Services.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(null, src, services, 10);
    assertEquals(1, result);

    ArgumentCaptor<Runnable> asyncCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).execute(asyncCaptor.capture());
    Runnable asyncTask = asyncCaptor.getValue();
    asyncTask.run();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Collection<UUID>> lookupCaptor =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(players).byUuidBulk(lookupCaptor.capture());
    assertEquals(Set.of(first, second), Set.copyOf(lookupCaptor.getValue()));
    verify(players, never()).byUuid(any());

    ArgumentCaptor<Runnable> mainCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(server).execute(mainCaptor.capture());
    Runnable mainThreadTask = mainCaptor.getValue();
    mainThreadTask.run();

    verify(src, times(3)).sendFeedback(any(), eq(false));
  }

  private static Players.PlayerRef playerRef(UUID uuid, String name) {
    return new Players.PlayerRef() {
      @Override
      public UUID uuid() {
        return uuid;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public long createdAtS() {
        return 0L;
      }

      @Override
      public long updatedAtS() {
        return 0L;
      }

      @Override
      public Long seenAtS() {
        return 0L;
      }

      @Override
      public long balanceUnits() {
        return 0L;
      }
    };
  }
}
