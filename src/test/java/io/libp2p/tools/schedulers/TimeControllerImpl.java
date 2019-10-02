package io.libp2p.tools.schedulers;

import com.google.common.collect.TreeMultimap;

import java.util.Comparator;
import java.util.NavigableSet;
import java.util.concurrent.atomic.AtomicLong;

public class TimeControllerImpl implements TimeController {
  private static AtomicLong id = new AtomicLong();
  TimeController parent;

  private static class OrderedTask {
    final long order = id.incrementAndGet();
    final Task task;

    public OrderedTask(Task task) {
      this.task = task;
    }

    public long getOrder() {
      return order;
    }
  }

  TreeMultimap<Long, OrderedTask> tasks = TreeMultimap.create(
          Comparator.naturalOrder(), Comparator.comparing(OrderedTask::getOrder));
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
      OrderedTask orderedTask = tasks.values().iterator().next();
      Task task = orderedTask.task;
      if (task.getTime() <= newTime) {
        curTime = task.getTime();
        tasks.remove(task.getTime(), orderedTask);
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

    tasks.put(task.getTime(), new OrderedTask(task));
  }

  @Override
  public void cancelTask(Task task) {
    if (parent != null) {
      parent.cancelTask(task);
      return;
    }

    NavigableSet<OrderedTask> tasks = this.tasks.get(task.getTime());
    for (OrderedTask orderedTask : tasks) {
      if (orderedTask.task == task) {
        this.tasks.remove(task.getTime(), orderedTask);
        return;
      }
    }
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
