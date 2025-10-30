/* Holarki © 2025 — MIT */
package dev.holarki.modules.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

class SchedulerCronTest {

  @Test
  void cronWithDayAndDowMatchesEitherField() throws Exception {
    Class<?> cronClass = Class.forName("dev.holarki.modules.scheduler.SchedulerEngine$Cron");

    Method parse = cronClass.getDeclaredMethod("parse", String.class);
    parse.setAccessible(true);
    Object cron = parse.invoke(null, "0 45 4 1 * 1");

    Method next = cronClass.getDeclaredMethod("next", Instant.class);
    next.setAccessible(true);

    Instant reference = LocalDateTime.of(2025, 1, 1, 4, 44, 59).toInstant(ZoneOffset.UTC);
    Instant first = (Instant) next.invoke(cron, reference);
    assertEquals(LocalDateTime.of(2025, 1, 1, 4, 45).toInstant(ZoneOffset.UTC), first);

    Instant second = (Instant) next.invoke(cron, first.plusSeconds(1));
    assertEquals(LocalDateTime.of(2025, 1, 6, 4, 45).toInstant(ZoneOffset.UTC), second);

    Instant third = (Instant) next.invoke(cron, second.plusSeconds(1));
    assertEquals(LocalDateTime.of(2025, 2, 1, 4, 45).toInstant(ZoneOffset.UTC), third);
  }

  @Test
  void cronWithDayAndDowSchedulesAllOccurrences() throws Exception {
    Class<?> cronClass = Class.forName("dev.holarki.modules.scheduler.SchedulerEngine$Cron");

    Method parse = cronClass.getDeclaredMethod("parse", String.class);
    parse.setAccessible(true);
    Object cron = parse.invoke(null, "0 0 12 15 * 1");

    Method next = cronClass.getDeclaredMethod("next", Instant.class);
    next.setAccessible(true);

    Instant reference = LocalDateTime.of(2025, 5, 15, 11, 59, 59).toInstant(ZoneOffset.UTC);
    Instant first = (Instant) next.invoke(cron, reference);
    assertEquals(LocalDateTime.of(2025, 5, 15, 12, 0).toInstant(ZoneOffset.UTC), first);

    Instant second = (Instant) next.invoke(cron, first.plusSeconds(1));
    assertEquals(LocalDateTime.of(2025, 5, 19, 12, 0).toInstant(ZoneOffset.UTC), second);

    Instant third = (Instant) next.invoke(cron, second.plusSeconds(1));
    assertEquals(LocalDateTime.of(2025, 5, 26, 12, 0).toInstant(ZoneOffset.UTC), third);
  }

  @Test
  void cronRejectsZeroOrNegativeSteps() throws Exception {
    Class<?> cronClass = Class.forName("dev.holarki.modules.scheduler.SchedulerEngine$Cron");
    Method parse = cronClass.getDeclaredMethod("parse", String.class);
    parse.setAccessible(true);

    assertThrows(IllegalArgumentException.class, toExecutable(parse, "0 */0 * * * *"));
    assertThrows(IllegalArgumentException.class, toExecutable(parse, "0 */-5 * * * *"));
  }

  @Test
  void cronRejectsInvertedRanges() throws Exception {
    Class<?> cronClass = Class.forName("dev.holarki.modules.scheduler.SchedulerEngine$Cron");
    Method parse = cronClass.getDeclaredMethod("parse", String.class);
    parse.setAccessible(true);

    assertThrows(IllegalArgumentException.class, toExecutable(parse, "0 0 12 10-5 * *"));
  }

  private static Executable toExecutable(Method parse, String expression) {
    return () -> {
      try {
        parse.invoke(null, expression);
      } catch (java.lang.reflect.InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new RuntimeException(cause);
      }
    };
  }
}
