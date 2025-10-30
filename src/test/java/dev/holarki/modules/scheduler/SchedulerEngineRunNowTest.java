/* Holarki © 2025 — MIT */
package dev.holarki.modules.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.core.Metrics;
import dev.holarki.core.Services;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SchedulerEngineRunNowTest {

  @Test
  void runNowRejectsWhileQueued() throws Exception {
    StubScheduler scheduler = new StubScheduler();
    TestJob job = new TestJob();
    SchedulerEngine engine = createEngineWithJob("queued", job, scheduler);

    SchedulerService.RunResult first = engine.runNow("queued");
    assertEquals(SchedulerService.RunResult.QUEUED, first);

    SchedulerService.RunResult second = engine.runNow("queued");
    assertEquals(SchedulerService.RunResult.IN_PROGRESS, second);

    Future<?> future = scheduler.runNextAsync();
    future.get(1, TimeUnit.SECONDS);

    SchedulerService.RunResult third = engine.runNow("queued");
    assertEquals(SchedulerService.RunResult.QUEUED, third);
  }

  @Test
  void runNowRejectsWhileRunning() throws Exception {
    StubScheduler scheduler = new StubScheduler();
    TestJob job = new TestJob();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    job.onRun(started, release);

    SchedulerEngine engine = createEngineWithJob("running", job, scheduler);

    SchedulerService.RunResult first = engine.runNow("running");
    assertEquals(SchedulerService.RunResult.QUEUED, first);

    Future<?> future = scheduler.runNextAsync();
    assertTrue(started.await(1, TimeUnit.SECONDS));

    SchedulerService.RunResult second = engine.runNow("running");
    assertEquals(SchedulerService.RunResult.IN_PROGRESS, second);

    release.countDown();
    future.get(1, TimeUnit.SECONDS);
  }

  private SchedulerEngine createEngineWithJob(
      String name, Runnable task, StubScheduler scheduler) throws Exception {
    SchedulerEngine engine = new SchedulerEngine();
    TestServices services = new TestServices(scheduler);

    Field servicesField = SchedulerEngine.class.getDeclaredField("services");
    servicesField.setAccessible(true);
    servicesField.set(engine, services);

    Class<?> jobHandleClass =
        Class.forName("dev.holarki.modules.scheduler.SchedulerEngine$JobHandle");
    Constructor<?> ctor =
        jobHandleClass.getDeclaredConstructor(String.class, String.class, Runnable.class, String.class);
    ctor.setAccessible(true);
    Object job = ctor.newInstance(name, "0 0 0 1 1 *", task, "test job");

    Method register = SchedulerEngine.class.getDeclaredMethod("register", jobHandleClass);
    register.setAccessible(true);
    register.invoke(engine, job);

    return engine;
  }

  private static final class TestServices implements Services {
    private final ScheduledExecutorService scheduler;

    TestServices(ScheduledExecutorService scheduler) {
      this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public dev.holarki.api.Players players() {
      return null;
    }

    @Override
    public dev.holarki.api.Wallets wallets() {
      return null;
    }

    @Override
    public dev.holarki.api.Attributes attributes() {
      return null;
    }

    @Override
    public dev.holarki.api.events.CoreEvents events() {
      return null;
    }

    @Override
    public dev.holarki.api.storage.ModuleDatabase database() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService scheduler() {
      return scheduler;
    }

    @Override
    public Metrics metrics() {
      return Services.super.metrics();
    }

    @Override
    public dev.holarki.api.Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
      // no-op
    }
  }

  private static final class TestJob implements Runnable {
    private final AtomicReference<CountDownLatch> started =
        new AtomicReference<>(new CountDownLatch(0));
    private final AtomicReference<CountDownLatch> release =
        new AtomicReference<>(new CountDownLatch(0));

    void onRun(CountDownLatch start, CountDownLatch done) {
      started.set(start);
      release.set(done);
    }

    @Override
    public void run() {
      started.get().countDown();
      try {
        release.get().await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class StubScheduler implements ScheduledExecutorService {
    private final Deque<RunnableFuture<?>> immediate = new ArrayDeque<>();
    private final Deque<ScheduledTask> scheduled = new ArrayDeque<>();
    private volatile boolean shutdown;

    Future<?> runNextAsync() {
      RunnableFuture<?> future = immediate.pollFirst();
      if (future == null) {
        return FutureTaskWithResult.completed();
      }
      Thread worker = new Thread(future::run);
      worker.start();
      return future;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      ScheduledTask task = new ScheduledTask(command, delay, unit);
      scheduled.add(task);
      return task;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
      if (shutdown) {
        throw new java.util.concurrent.RejectedExecutionException("shutdown");
      }
      RunnableFuture<?> future = new FutureTask<>(task, null);
      immediate.add(future);
      return future;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
      submit(command);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && immediate.isEmpty() && scheduled.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return isTerminated();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ScheduledTask extends FutureTask<Void>
      implements ScheduledFuture<Void> {
    private final long delayNanos;

    ScheduledTask(Runnable command, long delay, TimeUnit unit) {
      super(command, null);
      this.delayNanos = unit.toNanos(delay);
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      return Long.compare(
          getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }
  }

  private static final class FutureTaskWithResult {
    private FutureTaskWithResult() {}

    static Future<?> completed() {
      FutureTask<Void> task = new FutureTask<>(() -> null);
      task.run();
      return task;
    }
  }
}
