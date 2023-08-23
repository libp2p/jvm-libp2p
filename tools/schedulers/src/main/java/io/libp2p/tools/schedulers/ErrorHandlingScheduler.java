package io.libp2p.tools.schedulers;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

// import reactor.core.Disposable;

public class ErrorHandlingScheduler implements Scheduler {

  private final Scheduler delegate;
  private final Consumer<Throwable> errorHandler;

  //  private reactor.core.scheduler.Scheduler cachedReactor;

  public ErrorHandlingScheduler(Scheduler delegate, Consumer<Throwable> errorHandler) {
    this.delegate = delegate;
    this.errorHandler = errorHandler;
  }

  @Override
  public <T> CompletableFuture<T> execute(Callable<T> task) {
    return delegate.execute(task);
  }

  @Override
  public <T> CompletableFuture<T> executeWithDelay(Duration delay, Callable<T> task) {
    return delegate.executeWithDelay(delay, task);
  }

  @Override
  public CompletableFuture<Void> executeAtFixedRate(
      Duration initialDelay, Duration period, RunnableEx task) {
    return delegate.executeAtFixedRate(initialDelay, period, () -> runAndHandleError(task));
  }

  @Override
  public CompletableFuture<Void> execute(RunnableEx task) {
    return delegate.execute(() -> runAndHandleError(task));
  }

  @Override
  public CompletableFuture<Void> executeWithDelay(Duration delay, RunnableEx task) {
    return delegate.executeWithDelay(delay, () -> runAndHandleError(task));
  }

  @Override
  public long getCurrentTime() {
    return delegate.getCurrentTime();
  }

  //  @Override
  //  public reactor.core.scheduler.Scheduler toReactor() {
  //    if (cachedReactor == null) {
  //      cachedReactor = new ErrorHandlingReactorScheduler(delegate.toReactor(),
  //          delegate::getCurrentTime);
  //    }
  //    return cachedReactor;
  //  }

  private void runAndHandleError(RunnableEx runnable) throws Exception {
    try {
      runnable.run();
    } catch (Exception e) {
      errorHandler.accept(e);
      throw e;
    } catch (Throwable t) {
      errorHandler.accept(t);
      throw new ExecutionException(t);
    }
  }

  //  private class ErrorHandlingReactorScheduler extends DelegatingReactorScheduler {
  //
  //    public ErrorHandlingReactorScheduler(reactor.core.scheduler.Scheduler delegate,
  //        Supplier<Long> timeSupplier) {
  //      super(delegate, timeSupplier);
  //    }
  //
  //    @Nonnull
  //    @Override
  //    public Disposable schedule(@Nonnull Runnable task) {
  //      return super.schedule(() -> runAndHandleError(task));
  //    }
  //
  //    @Nonnull
  //    @Override
  //    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
  //      return super.schedule(() -> runAndHandleError(task), delay, unit);
  //    }
  //
  //    @Nonnull
  //    @Override
  //    public Disposable schedulePeriodically(Runnable task, long initialDelay, long period,
  //        TimeUnit unit) {
  //      return super.schedulePeriodically(() -> runAndHandleError(task), initialDelay, period,
  // unit);
  //    }
  //
  //    @Nonnull
  //    @Override
  //    public Worker createWorker() {
  //      return new DelegateWorker(super.createWorker()) {
  //        @Nonnull
  //        @Override
  //        public Disposable schedule(@Nonnull Runnable task) {
  //          return super.schedule(() -> runAndHandleError(task));
  //        }
  //
  //        @Nonnull
  //        @Override
  //        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
  //          return super.schedule(() -> runAndHandleError(task), delay, unit);
  //        }
  //
  //        @Nonnull
  //        @Override
  //        public Disposable schedulePeriodically(Runnable task, long initialDelay, long period,
  //            TimeUnit unit) {
  //          return super.schedulePeriodically(() -> runAndHandleError(task), initialDelay, period,
  // unit);
  //        }
  //      };
  //    }
  //
  //    private void runAndHandleError(Runnable runnable) {
  //      try {
  //        runnable.run();
  //      } catch (Throwable t) {
  //        errorHandler.accept(t);
  //        throw new RuntimeException(t);
  //      }
  //    }
  //  }
}
