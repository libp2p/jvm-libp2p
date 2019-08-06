package io.libp2p.tools.schedulers;

import com.google.common.collect.TreeMultimap;

import java.util.Comparator;

public class TimeControllerImpl implements TimeController {
  TimeController parent;

  TreeMultimap<Long, Task> tasks = TreeMultimap.create(
          Comparator.naturalOrder(), Comparator.comparing(System::identityHashCode));
  long curTime;
  long timeShift;

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
    while (!tasks.isEmpty()) {
      Task task = tasks.values().iterator().next();
      if (task.getTime() <= newTime) {
        curTime = task.getTime();
        tasks.remove(task.getTime(), task);
        task.execute();
      } else {
        break;
      }
    }
    curTime = newTime;
  }

  @Override
  public void addTask(Task task) {
    if (parent != null) {
      parent.addTask(task);
      return;
    }

    tasks.put(task.getTime(), task);
  }

  @Override
  public void cancelTask(Task task) {
    if (parent != null) {
      parent.cancelTask(task);
      return;
    }

    tasks.remove(task.getTime(), task);
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
