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
final class SchedulerAdminCommands {

  private SchedulerAdminCommands() {}

  static void register(ModuleContext context, SchedulerService scheduler) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(scheduler, "scheduler");
    Services services = context.services();
    context.registerAdminCommandExtension(root -> attach(root, scheduler, services));
  }

  static void attach(
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
                  job.successCount,
                  job.failureCount,
                  error),
          false);
    }
    return 1;
  }

  private static int cmdJobsRun(
      final ServerCommandSource src, final SchedulerService scheduler, final String job) {
    boolean scheduled = scheduler.runNow(job);
    if (scheduled) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.ok", job), false);
      return 1;
    }
    src.sendFeedback(() -> Text.translatable("holarki.cmd.jobs.run.unknown", job), false);
    return 0;
  }

  private static int cmdBackupNow(
      final ServerCommandSource src, final SchedulerService scheduler) {
    boolean scheduled = scheduler.runNow("backup");
    if (scheduled) {
      src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.queued"), false);
      return 1;
    }
    src.sendFeedback(() -> Text.translatable("holarki.cmd.backup.fail", "job"), false);
    return 0;
  }
}
