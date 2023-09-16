package io.libp2p.tools.schedulers;

import java.time.Duration;

/**
 * Special Schedulers implementation which is mostly suitable for testing and simulation. The system
 * time is controlled manually and all the schedulers execute tasks according to this time. Initial
 * system time is equal to 0
 */
public interface ControlledSchedulers extends Schedulers {

  /**
   * Sets current time.
   *
   * @throws IllegalStateException if this instance is dependent on a parent {@link TimeController}
   * @see TimeController#setTime(long)
   */
  default void setCurrentTime(long newTime) {
    getTimeController().setTime(newTime);
  }

  /** Just a handy helper method for {@link #setCurrentTime(long)} */
  default void addTime(Duration duration) {
    addTime(duration.toMillis());
  }

  /** Just a handy helper method for {@link #setCurrentTime(long)} */
  default void addTime(long millis) {
    setCurrentTime(getCurrentTime() + millis);
  }

  /**
   * Returns {@link TimeController} which manages tasks ordered execution and supplies current time
   * for this instance
   */
  TimeController getTimeController();
}
