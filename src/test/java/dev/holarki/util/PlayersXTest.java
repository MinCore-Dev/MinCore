/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.api.HolarkiApi;
import dev.holarki.api.Players;
import dev.holarki.core.Services;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class PlayersXTest {
  private TestPlayers players;

  @BeforeEach
  void setUp() throws Exception {
    resetApi();
    players = new TestPlayers();
    Services services = Mockito.mock(Services.class);
    Mockito.when(services.players()).thenReturn(players);
    HolarkiApi.bootstrap(services);
  }

  @AfterEach
  void tearDown() throws Exception {
    resetApi();
  }

  @Test
  void resolveNameExactReturnsUuidWhenSingleExactMatchAfterFiltering() {
    UUID expected = UUID.randomUUID();
    players.setByNameAll(
        List.of(
            new TestPlayerRef(expected, "Alex"),
            new TestPlayerRef(UUID.randomUUID(), "alex")));

    Optional<UUID> result = PlayersX.resolveNameExact("Alex");

    assertTrue(result.isPresent());
    assertEquals(expected, result.orElseThrow());
  }

  @Test
  void resolveNameExactReturnsEmptyWhenCaseDoesNotMatch() {
    players.setByNameAll(List.of(new TestPlayerRef(UUID.randomUUID(), "alex")));

    Optional<UUID> result = PlayersX.resolveNameExact("Alex");

    assertTrue(result.isEmpty());
  }

  @Test
  void resolveNameExactReturnsEmptyWhenMultipleExactMatches() {
    players.setByNameAll(
        List.of(new TestPlayerRef(UUID.randomUUID(), "Alex"), new TestPlayerRef(UUID.randomUUID(), "Alex")));

    Optional<UUID> result = PlayersX.resolveNameExact("Alex");

    assertTrue(result.isEmpty());
  }

  private static void resetApi() throws Exception {
    Field services = HolarkiApi.class.getDeclaredField("services");
    services.setAccessible(true);
    services.set(null, null);
    Field ledger = HolarkiApi.class.getDeclaredField("ledger");
    ledger.setAccessible(true);
    ledger.set(null, null);
  }

  private static final class TestPlayers implements Players {
    private List<PlayerRef> byNameAllResult = List.of();

    void setByNameAll(List<PlayerRef> result) {
      this.byNameAllResult = List.copyOf(result);
    }

    @Override
    public Optional<PlayerRef> byUuid(UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PlayerRef> byName(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PlayerRef> byNameAll(String name) {
      return byNameAllResult;
    }

    @Override
    public void upsertSeen(UUID uuid, String name, long seenAtS) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void iteratePlayers(Consumer<PlayerRef> consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private record TestPlayerRef(UUID uuid, String name) implements Players.PlayerRef {
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
      return null;
    }

    @Override
    public long balanceUnits() {
      return 0L;
    }
  }
}
