package io.libp2p.tools.schedulers;

import java.util.concurrent.ScheduledExecutorService;

/**
 * The collection of standard Schedulers, Scheduler factory and system time supplier
 *
 * <p>For debugging and testing the default <code>Schedulers</code> instance can be replaced with
 * appropriate one
 */
public abstract class AbstractSchedulers implements Schedulers {
  private static final int BLOCKING_THREAD_COUNT = 128;

  private Scheduler cpuHeavyScheduler;
  private Scheduler blockingScheduler;
  private Scheduler eventsScheduler;
  private ScheduledExecutorService eventsExecutor;

  @Override
  public long getCurrentTime() {
    return System.currentTimeMillis();
  }

  protected abstract ScheduledExecutorService createExecutor(String namePattern, int threads);

  protected Scheduler createExecutorScheduler(ScheduledExecutorService executorService) {
    return new ExecutorScheduler(executorService, this::getCurrentTime);
  }

  @Override
  public Scheduler cpuHeavy() {
    if (cpuHeavyScheduler == null) {
      synchronized (this) {
        if (cpuHeavyScheduler == null) {
          cpuHeavyScheduler = createCpuHeavy();
        }
      }
    }
    return cpuHeavyScheduler;
  }

  protected Scheduler createCpuHeavy() {
    return createExecutorScheduler(createCpuHeavyExecutor());
  }

  protected ScheduledExecutorService createCpuHeavyExecutor() {
    return createExecutor("Schedulers-cpuHeavy-%d", Runtime.getRuntime().availableProcessors());
  }

  @Override
  public Scheduler blocking() {
    if (blockingScheduler == null) {
      synchronized (this) {
        if (blockingScheduler == null) {
          blockingScheduler = createBlocking();
        }
      }
    }
    return blockingScheduler;
  }

  protected Scheduler createBlocking() {
    return createExecutorScheduler(createBlockingExecutor());
  }

  protected ScheduledExecutorService createBlockingExecutor() {
    return createExecutor("Schedulers-blocking-%d", BLOCKING_THREAD_COUNT);
  }

  @Override
  public Scheduler events() {
    if (eventsScheduler == null) {
      synchronized (this) {
        if (eventsScheduler == null) {
          eventsScheduler = createEvents();
        }
      }
    }
    return eventsScheduler;
  }

  protected Scheduler createEvents() {
    return createExecutorScheduler(getEventsExecutor());
  }

  protected ScheduledExecutorService getEventsExecutor() {
    if (eventsExecutor == null) {
      eventsExecutor = createExecutor("Schedulers-events", 1);
    }
    return eventsExecutor;
  }

  @Override
  public Scheduler newParallelDaemon(String threadNamePattern, int threadPoolCount) {
    return createExecutorScheduler(createExecutor(threadNamePattern, threadPoolCount));
  }
}
