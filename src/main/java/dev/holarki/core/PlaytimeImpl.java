/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.Playtime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link Playtime} implementation.
 *
 * <p>Thread-safe for typical server usage. No persistence is performed by this class.
 */
public final class PlaytimeImpl implements Playtime {

  /** Creates a new in-memory playtime tracker. */
  public PlaytimeImpl() {}

  /** Accumulated finished-session seconds per player (does not include the live session). */
  private final Map<UUID, Long> totalSeconds = new ConcurrentHashMap<>();

  /** Start timestamp (epoch seconds) for currently-online players. */
  private final Map<UUID, Long> liveSessionStartS = new ConcurrentHashMap<>();

  @Override
  public void onJoin(UUID player) {
    if (player == null) return;
    liveSessionStartS.putIfAbsent(player, nowS());
  }

  @Override
  public void onQuit(UUID player) {
    if (player == null) return;
    Long start = liveSessionStartS.remove(player);
    if (start != null) {
      long add = Math.max(0, nowS() - start);
      totalSeconds.merge(player, add, Long::sum);
    }
  }

  @Override
  public long seconds(UUID player) {
    if (player == null) return 0L;
    long base = totalSeconds.getOrDefault(player, 0L);
    Long start = liveSessionStartS.get(player);
    if (start != null) {
      long live = Math.max(0, nowS() - start);
      return base + live;
    }
    return base;
  }

  @Override
  public void reset(UUID player) {
    if (player == null) return;
    totalSeconds.remove(player);
    liveSessionStartS.computeIfPresent(player, (id, start) -> nowS());
  }

  @Override
  public List<Playtime.Entry> top(int limit) {
    int lim = Math.max(1, Math.min(1000, limit));
    Map<UUID, Long> merged = new HashMap<>(totalSeconds.size() + liveSessionStartS.size());
    merged.putAll(totalSeconds);

    long now = nowS();
    liveSessionStartS.forEach(
        (player, start) -> {
          long live = Math.max(0, now - start);
          merged.merge(player, live, Long::sum);
        });

    List<Playtime.Entry> all = new ArrayList<>(merged.size());
    merged.forEach((player, seconds) -> all.add(new Playtime.Entry(player, seconds)));
    all.sort(Comparator.comparingLong(Playtime.Entry::seconds).reversed());
    return all.size() > lim ? List.copyOf(all.subList(0, lim)) : List.copyOf(all);
  }

  @Override
  public Set<UUID> onlinePlayers() {
    return java.util.Collections.unmodifiableSet(liveSessionStartS.keySet());
  }

  @Override
  public void close() {
    liveSessionStartS.clear();
    totalSeconds.clear();
  }

  private static long nowS() {
    return Instant.now().getEpochSecond();
  }
}
