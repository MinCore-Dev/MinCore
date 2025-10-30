/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.scheduler;

import java.time.Instant;
import java.util.List;

/** Public API exposed by the scheduler module for commands and diagnostics. */
public interface SchedulerService {

  /**
   * Returns an immutable snapshot of all registered jobs.
   *
   * @return list of job status snapshots
   */
  List<JobStatus> jobs();

  /**
   * Schedules the named job for immediate execution.
   *
   * @param name job identifier
   * @return {@link RunResult#QUEUED} when the job was submitted, or another value describing why it
   *     could not be queued
   */
  RunResult runNow(String name);

  /** Immutable view of a scheduled job's current state. */
  public static final class JobStatus implements Comparable<JobStatus> {
    /** Unique job identifier. */
    public final String name;

    /** Cron expression configured for the job. */
    public final String schedule;

    /** Short human-readable description. */
    public final String description;

    /** Whether the job is currently disabled. */
    public final boolean disabled;

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

    JobStatus(String name, String schedule, String description, boolean disabled) {
      this.name = name;
      this.schedule = schedule;
      this.description = description;
      this.disabled = disabled;
    }

    JobStatus(JobStatus other) {
      this.name = other.name;
      this.schedule = other.schedule;
      this.description = other.description;
      this.disabled = other.disabled;
      this.nextRun = other.nextRun;
      this.lastRun = other.lastRun;
      this.running = other.running;
      this.lastError = other.lastError;
      this.successCount = other.successCount;
      this.failureCount = other.failureCount;
    }

    JobStatus copy() {
      return new JobStatus(this);
    }

    @Override
    public int compareTo(JobStatus o) {
      return this.name.compareToIgnoreCase(o.name);
    }
  }

  /** Outcome of a {@link #runNow(String)} request. */
  enum RunResult {
    /** The job was accepted and queued for immediate execution. */
    QUEUED,

    /** The job is already running or waiting to run. */
    IN_PROGRESS,

    /** The job could not be found. */
    UNKNOWN,

    /** The scheduler service is unavailable (module stopped or disabled). */
    DISABLED
  }
}
