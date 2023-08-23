package io.libp2p.tools.schedulers;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

public class ControlledSchedulersImpl extends AbstractSchedulers implements ControlledSchedulers {

  private TimeController timeController = new TimeControllerImpl();

  @Override
  public long getCurrentTime() {
    return timeController.getTime();
  }

  @Override
  public void setCurrentTime(long newTime) {
    timeController.setTime(newTime);
  }

  @Override
  protected Scheduler createExecutorScheduler(ScheduledExecutorService executorService) {
    return new ErrorHandlingScheduler(
        new ExecutorScheduler(executorService, this::getCurrentTime), e -> e.printStackTrace());
  }

  @Override
  protected ScheduledExecutorService createExecutor(String namePattern, int threads) {
    ControlledExecutorServiceImpl service =
        new ControlledExecutorServiceImpl(createDelegateExecutor());
    service.setTimeController(timeController);
    return service;
  }

  @Override
  public TimeController getTimeController() {
    return timeController;
  }

  protected Executor createDelegateExecutor() {
    return Runnable::run;
  }
}
