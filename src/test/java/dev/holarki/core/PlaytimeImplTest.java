/* Holarki © 2025 — MIT */
package dev.holarki.core;

import dev.holarki.api.Playtime;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlaytimeImplTest {

  @SuppressWarnings("unchecked")
  private static Map<UUID, Long> totals(PlaytimeImpl impl) throws Exception {
    Field field = PlaytimeImpl.class.getDeclaredField("totalSeconds");
    field.setAccessible(true);
    return (Map<UUID, Long>) field.get(impl);
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Long> live(PlaytimeImpl impl) throws Exception {
    Field field = PlaytimeImpl.class.getDeclaredField("liveSessionStartS");
    field.setAccessible(true);
    return (Map<UUID, Long>) field.get(impl);
  }

  @Test
  void topMatchesLegacyMergingBehavior() throws Exception {
    PlaytimeImpl impl = new PlaytimeImpl();

    Map<UUID, Long> totals = totals(impl);
    Map<UUID, Long> live = live(impl);

    UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    totals.put(a, 900L);
    totals.put(b, 400L);

    long now = Instant.now().getEpochSecond();
    live.put(a, now - 120L);
    live.put(c, now - 300L);

    int limit = 5;
    List<Playtime.Entry> expected = legacyTopSnapshot(totals, live, limit, now);
    List<Playtime.Entry> actual = impl.top(limit);

    org.junit.jupiter.api.Assertions.assertEquals(expected.size(), actual.size());

    for (int i = 0; i < expected.size(); i++) {
      Playtime.Entry expectedEntry = expected.get(i);
      Playtime.Entry actualEntry = actual.get(i);
      org.junit.jupiter.api.Assertions.assertEquals(expectedEntry.player(), actualEntry.player());
      long diff = Math.abs(expectedEntry.seconds() - actualEntry.seconds());
      org.junit.jupiter.api.Assertions.assertTrue(
          diff <= 3,
          () ->
              "seconds mismatch for %s expected %d actual %d"
                  .formatted(
                      expectedEntry.player(), expectedEntry.seconds(), actualEntry.seconds()));
    }
  }

  private static List<Playtime.Entry> legacyTopSnapshot(
      Map<UUID, Long> totals, Map<UUID, Long> live, int limit, long now) {
    int lim = Math.max(1, Math.min(1000, limit));
    List<Playtime.Entry> all = new ArrayList<>(totals.size() + live.size());

    totals.forEach((player, seconds) -> all.add(new Playtime.Entry(player, seconds)));

    for (Map.Entry<UUID, Long> entry : live.entrySet()) {
      long liveSeconds = Math.max(0L, now - entry.getValue());
      boolean merged = false;
      for (int i = 0; i < all.size(); i++) {
        Playtime.Entry existing = all.get(i);
        if (existing.player().equals(entry.getKey())) {
          all.set(
              i, new Playtime.Entry(existing.player(), existing.seconds() + liveSeconds));
          merged = true;
          break;
        }
      }
      if (!merged) {
        all.add(new Playtime.Entry(entry.getKey(), liveSeconds));
      }
    }

    all.sort(Comparator.comparingLong(Playtime.Entry::seconds).reversed());
    List<Playtime.Entry> limited =
        all.size() > lim ? all.subList(0, lim) : all;
    return List.copyOf(limited);
  }
}
