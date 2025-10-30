/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.scheduler;

import dev.holarki.api.ErrorCode;
import dev.holarki.core.BackupExporter;
import dev.holarki.core.Config;
import dev.holarki.core.Services;
import dev.holarki.core.SqlErrorCodes;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Instance-backed scheduling engine owned by the scheduler module. */
public final class SchedulerEngine implements SchedulerService {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");

  private final Map<String, JobHandle> jobs = new ConcurrentHashMap<>();
  private volatile Services services;

  /** Installs jobs described in config. */
  public synchronized void start(Services services, Config cfg) {
    this.services = Objects.requireNonNull(services, "services");
    Objects.requireNonNull(cfg, "cfg");

    jobs.clear();

    if (!cfg.modules().scheduler().enabled()) {
      LOG.info("(holarki) scheduler: disabled by config");
      return;
    }

    if (cfg.jobs().cleanup().idempotencySweep().enabled()) {
      Config.IdempotencySweep sweep = cfg.jobs().cleanup().idempotencySweep();
      register(
          new JobHandle(
              "cleanup.idempotencySweep",
              sweep.schedule(),
              () -> runIdempotencySweep(sweep),
              "Removes expired core_requests rows"));
    }

    if (cfg.jobs().backup().enabled()) {
      Config.Backup backupJob = cfg.jobs().backup();
      register(
          new JobHandle(
              "backup",
              backupJob.schedule(),
              () -> runBackup(cfg),
              "Exports JSONL backup"));

      if (backupJob.onMissed() == Config.OnMissed.RUN_AT_NEXT_STARTUP) {
        runNow("backup");
      }
    }
  }

  /** Cancels all scheduled jobs and clears state. */
  public synchronized void stop() {
    for (JobHandle job : jobs.values()) {
      ScheduledFuture<?> future = job.future;
      if (future != null) {
        future.cancel(false);
      }
    }
    jobs.clear();
    services = null;
  }

  @Override
  public List<JobStatus> jobs() {
    return jobs.values().stream().map(JobHandle::snapshot).sorted().collect(Collectors.toList());
  }

  @Override
  public RunResult runNow(String name) {
    Services svc = services;
    if (svc == null) {
      return RunResult.DISABLED;
    }
    JobHandle job = jobs.get(name);
    if (job == null) {
      return RunResult.UNKNOWN;
    }
    if (!job.tryQueueManual()) {
      return RunResult.IN_PROGRESS;
    }
    try {
      svc.scheduler().submit(() -> execute(job));
      return RunResult.QUEUED;
    } catch (RejectedExecutionException e) {
      job.clearManualQueued();
      LOG.warn("(holarki) scheduler: executor rejected job {}", job.name, e);
      return RunResult.UNKNOWN;
    }
  }

  private void register(JobHandle job) {
    jobs.put(job.name, job);
    schedule(job, Instant.now());
  }

  private void schedule(JobHandle job, Instant reference) {
    Services svc = services;
    if (svc == null) {
      return;
    }
    Cron cron = job.cron;
    Instant next = cron.next(reference.plusSeconds(1));
    job.status.nextRun = next;
    ScheduledExecutorService executor = svc.scheduler();
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

  private void execute(JobHandle job) {
    Instant start = Instant.now();
    job.clearManualQueued();
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
      job.clearManualQueued();
      schedule(job, Instant.now());
    }
  }

  private void runIdempotencySweep(Config.IdempotencySweep sweep) {
    Services svc = services;
    if (svc == null) {
      return;
    }
    int batch = Math.max(1, sweep.batchLimit());
    long now = Instant.now().getEpochSecond();
    long retentionDays = Math.max(0L, sweep.retentionDays());
    long retentionSeconds = retentionDays * 86_400L;
    boolean useCreatedCutoff = retentionSeconds > 0;
    long createdCutoff = now;
    if (useCreatedCutoff) {
      long candidate = now - retentionSeconds;
      createdCutoff = candidate < 0 ? 0L : candidate;
    }
    int deleted = 0;
    try (Connection c = svc.database().borrowConnection()) {
      c.setAutoCommit(true);
      String sql =
          useCreatedCutoff
              ? "DELETE FROM core_requests WHERE expires_at_s <= ? AND created_at_s <= ? LIMIT ?"
              : "DELETE FROM core_requests WHERE expires_at_s <= ? LIMIT ?";
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, now);
        int idx = 2;
        if (useCreatedCutoff) {
          ps.setLong(idx++, createdCutoff);
        }
        ps.setInt(idx, batch);
        boolean more = true;
        while (more) {
          int n = ps.executeUpdate();
          deleted += n;
          more = n == batch;
        }
      }
      if (deleted > 0) {
        LOG.info("(holarki) cleanup removed {} expired idempotency rows", deleted);
      }
    } catch (SQLException e) {
      logJobFailure("cleanup.idempotencySweep", e);
      throw new RuntimeException("idempotency sweep failed", e);
    }
  }

  private void runBackup(Config cfg) {
    Services svc = services;
    if (svc == null) {
      return;
    }
    try {
      BackupExporter.Result result = BackupExporter.exportAll(svc, cfg);
      LOG.info(
          "(holarki) backup complete: {} players={} attrs={} ledger={}",
          result.file().getFileName(),
          result.players(),
          result.attributes(),
          result.ledger());
    } catch (Exception e) {
      logJobFailure("backup", e);
      throw new RuntimeException("backup failed", e);
    }
  }

  private static void logJobFailure(String jobName, Throwable error) {
    if (error instanceof SQLException sql) {
      ErrorCode code = SqlErrorCodes.classify(sql);
      LOG.warn(
          "(holarki) code={} op={} message={} sqlState={} vendor={}",
          code,
          jobName,
          sql.getMessage(),
          sql.getSQLState(),
          sql.getErrorCode(),
          sql);
    } else {
      LOG.warn(
          "(holarki) code={} op={} message={}",
          ErrorCode.CONNECTION_LOST,
          jobName,
          error.getMessage(),
          error);
    }
  }

  private static final class JobHandle {
    final String name;
    final Cron cron;
    final Runnable task;
    final String description;
    final JobStatus status;
    volatile ScheduledFuture<?> future;
    volatile boolean manualQueued;
    final Object lock = new Object();

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

    boolean tryQueueManual() {
      synchronized (lock) {
        if (status.running || manualQueued) {
          return false;
        }
        manualQueued = true;
        return true;
      }
    }

    void clearManualQueued() {
      synchronized (lock) {
        manualQueued = false;
      }
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
      boolean dayMatches = days.matches(time.getDayOfMonth());
      boolean dowMatches = dows.matches(dow);
      boolean domDowMatches;
      if (days.wildcard() && dows.wildcard()) {
        domDowMatches = true;
      } else if (days.wildcard()) {
        domDowMatches = dowMatches;
      } else if (dows.wildcard()) {
        domDowMatches = dayMatches;
      } else {
        boolean allowDowFallback = time.getDayOfMonth() <= 7;
        domDowMatches = dayMatches || (allowDowFallback && dowMatches);
      }
      return seconds.matches(time.getSecond())
          && minutes.matches(time.getMinute())
          && hours.matches(time.getHour())
          && months.matches(time.getMonthValue())
          && domDowMatches;
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
      if (step <= 0) {
        throw new IllegalArgumentException("cron step must be positive: " + part);
      }
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
      if (start > end) {
        throw new IllegalArgumentException("invalid cron range: " + part);
      }
      for (int v = start; v <= end; v += step) {
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
}
