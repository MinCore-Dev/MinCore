/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.ErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Installs and manages background jobs (backup + idempotency sweep). */
public final class Scheduler {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private static final Map<String, JobHandle> JOBS = new ConcurrentHashMap<>();
  private static Services services;

  private Scheduler() {}

  /**
   * Installs jobs described in config.
   *
   * @param s service container exposing the scheduler + database helpers
   * @param cfg runtime configuration that describes enabled jobs
   */
  public static synchronized void install(Services s, Config cfg) {
    services = s;
    JOBS.clear();

    if (!cfg.modules().scheduler().enabled()) {
      LOG.info("(mincore) scheduler: disabled by config");
      return;
    }

    if (cfg.jobs().cleanup().idempotencySweep().enabled()) {
      register(
          new JobHandle(
              "cleanup.idempotencySweep",
              cfg.jobs().cleanup().idempotencySweep().schedule(),
              () -> runIdempotencySweep(cfg),
              "Removes expired core_requests rows"));
    }

    if (cfg.jobs().backup().enabled()) {
      register(
          new JobHandle(
              "backup",
              cfg.jobs().backup().schedule(),
              () -> runBackup(cfg),
              "Exports JSONL backup"));

      if (cfg.jobs().backup().onMissed() == Config.OnMissed.RUN_AT_NEXT_STARTUP) {
        runNow("backup");
      }
    }
  }

  /**
   * Snapshot view for commands.
   *
   * @return immutable list of job status snapshots
   */
  public static List<JobStatus> jobs() {
    return JOBS.values().stream().map(JobHandle::snapshot).sorted().collect(Collectors.toList());
  }

  /**
   * Runs the named job immediately.
   *
   * @param name job identifier, e.g., {@code "backup"}
   * @return {@code true} if the job was found and scheduled
   */
  public static boolean runNow(String name) {
    JobHandle job = JOBS.get(name);
    if (job == null) {
      return false;
    }
    services.scheduler().submit(() -> execute(job));
    return true;
  }

  /** Cancels all scheduled jobs and clears state. */
  public static synchronized void shutdown() {
    for (JobHandle job : JOBS.values()) {
      ScheduledFuture<?> future = job.future;
      if (future != null) {
        future.cancel(false);
      }
    }
    JOBS.clear();
    services = null;
  }

  private static void register(JobHandle job) {
    JOBS.put(job.name, job);
    schedule(job, Instant.now());
  }

  private static void schedule(JobHandle job, Instant reference) {
    Cron cron = job.cron;
    Instant next = cron.next(reference.plusSeconds(1));
    job.status.nextRun = next;
    ScheduledExecutorService executor = services.scheduler();
    long delayMs = Math.max(0, Duration.between(Instant.now(), next).toMillis());
    ScheduledFuture<?> future =
        executor.schedule(
            () -> {
              try {
                execute(job);
              } catch (Exception e) {
                logJobFailure(job.name, e);
              }
            },
            delayMs,
            TimeUnit.MILLISECONDS);
    ScheduledFuture<?> prev = job.future;
    job.future = future;
    if (prev != null) {
      prev.cancel(false);
    }
  }

  private static void execute(JobHandle job) {
    Instant start = Instant.now();
    job.status.running = true;
    job.status.lastRun = start;
    try {
      job.task.run();
      job.status.lastError = null;
      job.status.successCount++;
    } catch (Exception e) {
      job.status.lastError = e.getMessage();
      job.status.failureCount++;
      logJobFailure(job.name, e);
    } finally {
      job.status.running = false;
      schedule(job, Instant.now());
    }
  }

  private static void runIdempotencySweep(Config cfg) {
    Config.IdempotencySweep sweep = cfg.jobs().cleanup().idempotencySweep();
    int batch = Math.max(1, sweep.batchLimit());
    long cutoff =
        Instant.now().minus(Duration.ofDays(Math.max(0, sweep.retentionDays()))).getEpochSecond();
    int deleted = 0;
    try (Connection c = services.database().borrowConnection()) {
      c.setAutoCommit(true);
      String sql = "DELETE FROM core_requests WHERE expires_at_s < ? LIMIT ?";
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, cutoff);
        ps.setInt(2, batch);
        boolean more = true;
        while (more) {
          int n = ps.executeUpdate();
          deleted += n;
          more = n == batch;
        }
      }
      if (deleted > 0) {
        LOG.info("(mincore) cleanup removed {} expired idempotency rows", deleted);
      }
    } catch (SQLException e) {
      logJobFailure("cleanup.idempotencySweep", e);
      throw new RuntimeException("idempotency sweep failed", e);
    }
  }

  private static void runBackup(Config cfg) {
    try {
      BackupExporter.Result result = BackupExporter.exportAll(services, cfg);
      LOG.info(
          "(mincore) backup complete: {} players={} attrs={} ledger={}",
          result.file().getFileName(),
          result.players(),
          result.attributes(),
          result.ledger());
    } catch (Exception e) {
      logJobFailure("backup", e);
      throw new RuntimeException("backup failed", e);
    }
  }

  /** Immutable job status snapshot. */
  public static final class JobStatus implements Comparable<JobStatus> {
    /** Unique job identifier. */
    public final String name;

    /** Cron expression configured for the job. */
    public final String schedule;

    /** Short human-readable description. */
    public final String description;

    /** Next scheduled execution time in UTC. */
    public volatile Instant nextRun;

    /** Timestamp of the most recent execution. */
    public volatile Instant lastRun;

    /** Whether the job is currently executing. */
    public volatile boolean running;

    /** Last error message if execution failed. */
    public volatile String lastError;

    /** Successful run counter. */
    public volatile long successCount;

    /** Failed run counter. */
    public volatile long failureCount;

    private JobStatus(String name, String schedule, String description) {
      this.name = name;
      this.schedule = schedule;
      this.description = description;
    }

    private JobStatus copy() {
      JobStatus copy = new JobStatus(name, schedule, description);
      copy.nextRun = nextRun;
      copy.lastRun = lastRun;
      copy.running = running;
      copy.lastError = lastError;
      copy.successCount = successCount;
      copy.failureCount = failureCount;
      return copy;
    }

    @Override
    public int compareTo(JobStatus o) {
      return this.name.compareToIgnoreCase(o.name);
    }
  }

  private static final class JobHandle {
    final String name;
    final Cron cron;
    final Runnable task;
    final String description;
    final JobStatus status;
    volatile ScheduledFuture<?> future;

    JobHandle(String name, String cronExpr, Runnable task, String description) {
      this.name = name;
      this.cron = Cron.parse(cronExpr);
      this.task = task;
      this.description = description;
      this.status = new JobStatus(name, cronExpr, description);
    }

    JobStatus snapshot() {
      return status.copy();
    }
  }

  /** Minimal 6-field cron parser. */
  private record Cron(
      Field seconds, Field minutes, Field hours, Field days, Field months, Field dows) {
    static Cron parse(String expression) {
      String[] parts = expression.trim().split("\\s+");
      if (parts.length != 6) {
        throw new IllegalArgumentException("cron must have 6 fields");
      }
      return new Cron(
          Field.parse(parts[0], 0, 59),
          Field.parse(parts[1], 0, 59),
          Field.parse(parts[2], 0, 23),
          Field.parse(parts[3], 1, 31),
          Field.parse(parts[4], 1, 12),
          Field.parse(parts[5], 0, 7));
    }

    Instant next(Instant after) {
      Instant candidate = after;
      for (int i = 0; i < 366 * 24 * 60 * 60; i++) {
        LocalDateTime time = LocalDateTime.ofInstant(candidate, ZoneOffset.UTC);
        if (matches(time)) {
          return candidate;
        }
        candidate = candidate.plusSeconds(1);
      }
      throw new IllegalStateException("cron search exhausted");
    }

    private boolean matches(LocalDateTime time) {
      int dow = time.getDayOfWeek().getValue() % 7; // Sunday=0
      return seconds.matches(time.getSecond())
          && minutes.matches(time.getMinute())
          && hours.matches(time.getHour())
          && days.matches(time.getDayOfMonth())
          && months.matches(time.getMonthValue())
          && dows.matches(dow);
    }
  }

  private record Field(boolean wildcard, List<Integer> values) {
    static Field parse(String token, int min, int max) {
      token = token.toLowerCase(Locale.ROOT);
      if ("*".equals(token)) {
        return new Field(true, List.of());
      }
      List<Integer> vals = new ArrayList<>();
      for (String part : token.split(",")) {
        vals.addAll(parsePart(part, min, max));
      }
      vals = vals.stream().distinct().sorted().collect(Collectors.toList());
      return new Field(false, vals);
    }

    private static List<Integer> parsePart(String part, int min, int max) {
      List<Integer> out = new ArrayList<>();
      String[] stepSplit = part.split("/");
      String range = stepSplit[0];
      int step = stepSplit.length > 1 ? Integer.parseInt(stepSplit[1]) : 1;
      int start;
      int end;
      if ("*".equals(range)) {
        start = min;
        end = max;
      } else if (range.contains("-")) {
        String[] bounds = range.split("-");
        start = Integer.parseInt(bounds[0]);
        end = Integer.parseInt(bounds[1]);
      } else {
        start = Integer.parseInt(range);
        end = start;
      }
      if (start < min) start = min;
      if (end > max) end = max;
      for (int v = start; v <= end; v += Math.max(1, step)) {
        int value = (max == 7 && v == 7) ? 0 : v;
        if (value >= min && value <= max) {
          out.add(value);
        }
      }
      return out;
    }

    boolean matches(int value) {
      if (wildcard) {
        return true;
      }
      if (values.isEmpty()) {
        return false;
      }
      return values.contains(value);
    }
  }

  private static void logJobFailure(String jobName, Throwable error) {
    if (error instanceof SQLException sql) {
      ErrorCode code = SqlErrorCodes.classify(sql);
      LOG.warn(
          "(mincore) code={} op={} message={} sqlState={} vendor={}",
          code,
          jobName,
          sql.getMessage(),
          sql.getSQLState(),
          sql.getErrorCode(),
          sql);
    } else {
      LOG.warn(
          "(mincore) code={} op={} message={}",
          ErrorCode.CONNECTION_LOST,
          jobName,
          error.getMessage(),
          error);
    }
  }
}
