/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.modules.scheduler;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.holarki.core.Services;
import dev.holarki.core.modules.ModuleContext;
import dev.holarki.util.TimeDisplay;
import dev.holarki.util.TimePreference;
import dev.holarki.util.Timezones;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Scheduler-specific admin commands under `/holarki`. */
public final class SchedulerAdminCommands {

  private SchedulerAdminCommands() {}

  public static void register(ModuleContext context, SchedulerService scheduler) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(scheduler, "scheduler");
    Services services = context.services();
    context.registerAdminCommandExtension(root -> attach(root, scheduler, services));
  }

  public static void attach(
      final LiteralArgumentBuilder<ServerCommandSource> root,
      final SchedulerService scheduler,
      final Services services) {
    LiteralArgumentBuilder<ServerCommandSource> jobs = CommandManager.literal("jobs");
    jobs.then(
        CommandManager.literal("list")
            .executes(ctx -> cmdJobsList(ctx.getSource(), scheduler, services)));
    jobs.then(
        CommandManager.literal("run")
            .then(
                CommandManager.argument("job", StringArgumentType.string())
                    .executes(
                        ctx ->
                            cmdJobsRun(
                                ctx.getSource(),
                                scheduler,
                                StringArgumentType.getString(ctx, "job")))));
    root.then(jobs);

    root.then(
        CommandManager.literal("backup")
            .then(
                CommandManager.literal("now")
                    .executes(ctx -> cmdBackupNow(ctx.getSource(), scheduler))));
  }

  private static int cmdJobsList(
      final ServerCommandSource src, final SchedulerService scheduler, final Services services) {
    List<SchedulerService.JobStatus> jobs = scheduler.jobs();
    TimePreference pref = Timezones.preferences(src, services);
    String offset = TimeDisplay.offsetLabel(pref.zone());
    if (jobs.isEmpty()) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.list.none"), false);
      return 1;
    }
    src.sendFeedback(
        () ->
            Text.translatable(
                "holarki.cmd.jobs.list.header",
                pref.zone().getId(),
                offset,
                pref.clock().description()),
        false);
    for (SchedulerService.JobStatus job : jobs) {
      String next = job.nextRun != null ? TimeDisplay.formatDateTime(job.nextRun, pref) : "-";
      String last = job.lastRun != null ? TimeDisplay.formatDateTime(job.lastRun, pref) : "-";
      String error = job.lastError == null ? "" : job.lastError;
      Text state =
          job.disabled
              ? Text.translatable("holarki.cmd.jobs.list.state.disabled")
              : Text.translatable("holarki.cmd.jobs.list.state.enabled");
      src.sendFeedback(
          () ->
              Text.translatable(
                  "holarki.cmd.jobs.list.line",
                  job.name,
                  job.schedule,
                  job.description,
                  next,
                  last,
                  job.running,
                  state,
                  job.successCount,
                  job.failureCount,
                  error),
          false);
    }
    return 1;
  }

  private static int cmdJobsRun(
      final ServerCommandSource src, final SchedulerService scheduler, final String job) {
    SchedulerService.RunResult result = scheduler.runNow(job);
    return switch (result) {
      case QUEUED -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.ok", job), false);
        yield 1;
      }
      case IN_PROGRESS -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.active", job), false);
        yield 0;
      }
      case DISABLED -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.disabled", job), false);
        yield 0;
      }
      case UNKNOWN -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.unknown", job), false);
        yield 0;
      }
    };
  }

  private static int cmdBackupNow(
      final ServerCommandSource src, final SchedulerService scheduler) {
    SchedulerService.RunResult result = scheduler.runNow("backup");
    return switch (result) {
      case QUEUED -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.queued"), false);
        yield 1;
      }
      case IN_PROGRESS -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.busy"), false);
        yield 0;
      }
      case DISABLED -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.fail", "disabled"), false);
        yield 0;
      }
      case UNKNOWN -> {
        src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.fail", "job"), false);
        yield 0;
      }
    };
  }
}
