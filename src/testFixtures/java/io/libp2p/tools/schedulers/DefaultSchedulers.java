package io.libp2p.tools.schedulers;

import io.libp2p.guava.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DefaultSchedulers extends AbstractSchedulers {

  private static final Logger logger = Logger.getLogger(DefaultSchedulers.class.getName());

  private Consumer<Throwable> errorHandler = t -> logger.log(Level.SEVERE, "Unhandled exception:", t);
  private volatile boolean started;

  public void setErrorHandler(Consumer<Throwable> errorHandler) {
    if (started) {
      throw new IllegalStateException("ErrorHandler should be set up prior to any other calls");
    }
    this.errorHandler = errorHandler;
  }

  @Override
  protected Scheduler createExecutorScheduler(ScheduledExecutorService executorService) {
    return new ErrorHandlingScheduler(
        new ExecutorScheduler(executorService, this::getCurrentTime), errorHandler);
  }

  @Override
  protected ScheduledExecutorService createExecutor(String namePattern, int threads) {
    started = true;
    return Executors.newScheduledThreadPool(threads, createThreadFactory(namePattern));
  }

  protected ThreadFactory createThreadFactory(String namePattern) {
    return createThreadFactoryBuilder(namePattern).build();
  }

  protected ThreadFactoryBuilder createThreadFactoryBuilder(String namePattern) {
    return new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat(namePattern)
        .setUncaughtExceptionHandler((thread, thr) -> errorHandler.accept(thr));
  }
}
