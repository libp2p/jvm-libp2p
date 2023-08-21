package io.libp2p.tools.schedulers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Controls global time and execution order of child executors The instance can be either 'root'
 * (with no parent) or dependent on the parent controller. In the latter case all the calls
 * delegated to the parent controller which manages the list of tasks and the global time
 */
public interface TimeController {

  /** Abstract scheduled task */
  interface Task {

    long getTime();

    CompletableFuture<Void> execute();
  }

  /**
   * Returns this controller local time which differs from the parent time in case if time shift !=
   * 0
   */
  long getTime();

  /**
   * The method call is only valid for the 'root' controller Sets internal clock time and executes
   * any tasks scheduled in period from the previous time till new <code>currentTime</code>
   * inclusive. Periodic tasks are executed several times if scheduled so.
   *
   * @param newTime should be >= the last set time
   * @throws IllegalStateException if the controller is not root
   */
  void setTime(long newTime);

  /** Child executors should add new scheduled tasks via this method */
  void addTask(Task task);

  /** Child executors should cancel scheduled tasks via this method */
  void cancelTask(Task task);

  /** Sets the parent of this controller making it dependent */
  void setParent(TimeController parent);

  /**
   * Simulates system clock deviations All children executors will see current time shifted by
   * specified value
   */
  void setTimeShift(long timeShift);

  default void addTime(long ms) {
    setTime(getTime() + ms);
  }

  default void addTime(Duration duration) {
    addTime(duration.toMillis());
  }
}
