package io.libp2p.tools.schedulers;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class ControlledExecutorServiceImpl implements ControlledExecutorService {

  private class ScheduledTask<V> implements TimeController.Task {
    Callable<V> callable;
    final ScheduledFutureImpl<V> future = new ScheduledFutureImpl<V>(b -> cancel());
    long targetTime;

    public ScheduledTask(Callable<V> callable, long targetTime) {
      if (targetTime < getCurrentTime()) {
        throw new IllegalStateException(
            "Invalid target time: " + targetTime + " < " + getCurrentTime());
      }
      this.callable = callable;
      this.targetTime = targetTime;
    }

    void cancel() {
      timeController.cancelTask(this);
    }

    public CompletableFuture<Void> execute() {
      delegateExecutor.execute(
          () -> {
            try {
              V res = callable.call();
              future.delegate.complete(res);
            } catch (Exception e) {
              future.delegate.completeExceptionally(e);
            }
          });
      return future.delegate.thenApply(i -> null);
    }

    @Override
    public long getTime() {
      return targetTime;
    }

    @Override
    public String toString() {
      return targetTime + ": " + callable;
    }
  }

  private class ScheduledFutureImpl<V> implements ScheduledFuture<V> {
    final CompletableFuture<V> delegate = new CompletableFuture<>();
    private final Consumer<Boolean> canceller;

    public ScheduledFutureImpl(Consumer<Boolean> canceller) {
      this.canceller = canceller;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Delayed o) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      canceller.accept(mayInterruptIfRunning);
      return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
      return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
      return delegate.isDone();
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
      return delegate.get();
    }

    @Override
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return delegate.get(timeout, unit);
    }
  }

  private final Executor delegateExecutor;
  private final boolean immediateExecute = false;
  private TimeController timeController;

  public ControlledExecutorServiceImpl() {
    this(Runnable::run); // default immediate executor
  }

  public ControlledExecutorServiceImpl(TimeController timeController) {
    this(Runnable::run, timeController);
  }

  public ControlledExecutorServiceImpl(Executor delegateExecutor) {
    this(delegateExecutor, null);
  }

  public ControlledExecutorServiceImpl(Executor delegateExecutor, TimeController timeController) {
    this.delegateExecutor = delegateExecutor;
    this.timeController = timeController;
  }

  @Override
  public void setTimeController(TimeController timeController) {
    this.timeController = timeController;
  }

  public long getCurrentTime() {
    return timeController.getTime();
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    if (delay < 0) {
      delay = 0;
    }
    ScheduledTask<V> scheduledTask =
        new ScheduledTask<>(callable, getCurrentTime() + unit.toMillis(delay));
    timeController.addTask(scheduledTask);
    return scheduledTask.future;
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    ScheduledFuture<?>[] activeFut = new ScheduledFutureImpl[1];
    ScheduledFutureImpl<?> ret = new ScheduledFutureImpl<>(b -> activeFut[0].cancel(b));

    activeFut[0] =
        schedule(
            () -> {
              command.run();
              if (!activeFut[0].isCancelled()) {
                activeFut[0] = scheduleAtFixedRate(command, period, period, unit);
              }
              return null;
            },
            initialDelay,
            unit);

    return ret;
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return schedule(
        () -> {
          command.run();
          return null;
        },
        delay,
        unit);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    CompletableFuture<T> ret = new CompletableFuture<>();
    execute(
        () -> {
          try {
            ret.complete(task.call());
          } catch (Throwable e) {
            ret.completeExceptionally(e);
          }
        });
    return ret;
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return submit(
        () -> {
          task.run();
          return result;
        });
  }

  @Override
  public Future<?> submit(Runnable task) {
    return submit(task, null);
  }

  @Override
  public void execute(Runnable command) {
    if (immediateExecute) {
      delegateExecutor.execute(command);
    } else {
      schedule(command, 0, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return scheduleAtFixedRate(command, initialDelay, delay, unit);
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
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void shutdown() {}

  @Override
  public List<Runnable> shutdownNow() {
    return Collections.emptyList();
  }

  @Override
  public boolean isShutdown() {
    return false;
  }

  @Override
  public boolean isTerminated() {
    return false;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return false;
  }
}
