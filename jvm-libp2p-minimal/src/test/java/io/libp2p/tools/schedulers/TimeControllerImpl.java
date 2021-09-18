package io.libp2p.tools.schedulers;

import java.util.concurrent.atomic.AtomicLong;

public class TimeControllerImpl implements TimeController {
  private static AtomicLong id = new AtomicLong();
  TimeController parent;

  TaskQueue taskQueue = new TaskQueue();

  long curTime;
  long timeShift;
  private boolean executingImmediate = false;

  @Override
  public long getTime() {
    if (parent != null) {
      return parent.getTime() + timeShift;
    }

    return curTime;
  }

  @Override
  public void setTime(long newTime) {
    if (parent != null) {
      throw new IllegalStateException(
              "setTime() is allowed only for the topmost TimeController (without parent)");
    }
    if (newTime < curTime) {
      throw new IllegalArgumentException("newTime < curTime: " + newTime + ", " + curTime);
    }
    newTime += timeShift;
    while (!taskQueue.isEmpty() && taskQueue.getEarliestTime() <= newTime) {
      curTime = taskQueue.getEarliestTime();
      taskQueue.executeEarliest();
    }
    curTime = newTime;
  }

  @Override
  public void addTask(Task task) {
    if (parent != null) {
      parent.addTask(task);
      return;
    }

    taskQueue.add(task);

    if (getTime() == task.getTime() && !taskQueue.isExecuting()) {
      executeImmediateTasksNonRecursively();
    }
  }

  private void executeImmediateTasksNonRecursively() {
    if (!executingImmediate) {
      try {
        executingImmediate = true;
        setTime(getTime());
      } finally {
        executingImmediate = false;
      }
    }
  }

  @Override
  public void cancelTask(Task task) {
    if (parent != null) {
      parent.cancelTask(task);
      return;
    }

    taskQueue.remove(task);
  }

  @Override
  public void setParent(TimeController parent) {
    this.parent = parent;
    curTime = parent.getTime();
  }

  @Override
  public void setTimeShift(long timeShift) {
    this.timeShift = timeShift;
  }
}
