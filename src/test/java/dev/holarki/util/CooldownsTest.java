/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CooldownsTest {

  @Test
  void tryAcquireAllowsOnlyOneSuccessPerWindowUnderConcurrency() throws Exception {
    Cooldowns cooldowns = new Cooldowns();
    int threads = 32;
    int windows = 100;
    long cooldownSeconds = 5L;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int window = 0; window < windows; window++) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>(threads);
        long now = window * (cooldownSeconds + 1);
        for (int i = 0; i < threads; i++) {
          tasks.add(
              () -> {
                ready.countDown();
                start.await();
                return cooldowns.tryAcquire("player", cooldownSeconds, now);
              });
        }

        List<Future<Boolean>> futures = new ArrayList<>(threads);
        for (Callable<Boolean> task : tasks) {
          futures.add(pool.submit(task));
        }

        ready.await();
        start.countDown();

        int successes = 0;
        for (Future<Boolean> future : futures) {
          if (future.get()) {
            successes++;
          }
        }

        assertEquals(1, successes, "Only one caller should acquire the cooldown per window");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
