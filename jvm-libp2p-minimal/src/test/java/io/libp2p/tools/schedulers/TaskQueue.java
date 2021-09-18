package io.libp2p.tools.schedulers;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TaskQueue {

    private NavigableMap<Long, Queue<TimeController.Task>> tasks = Collections.synchronizedNavigableMap(new TreeMap<>());
    private boolean executing = false;

    public void add(TimeController.Task task) {
        tasks.computeIfAbsent(task.getTime(), t -> new ConcurrentLinkedQueue<>()).add(task);
    }

    public void remove(TimeController.Task task) {
        tasks.computeIfPresent(task.getTime(), (t, queue) -> {
            queue.remove(task);
            return queue;
        });
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public long getEarliestTime() {
        Map.Entry<Long, Queue<TimeController.Task>> entry = tasks.firstEntry();
        return entry == null ? 0 : entry.getKey();
    }

    private Queue<TimeController.Task> peekEarliest() {
        return tasks.get(getEarliestTime());
    }

    public void dropEarliest() {
        tasks.remove(getEarliestTime());
    }

    public void executeEarliest() {
        if (executing) return;
        executing = true;
        try {
            Queue<TimeController.Task> taskQueue = peekEarliest();
            if (taskQueue == null) return;

            Queue<CompletableFuture<Void>> resQueue = new LinkedBlockingQueue<>();

            drainQueue(taskQueue, resQueue);

            while (!resQueue.isEmpty()) {
                try {
                    resQueue.poll().get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            dropEarliest();
        } finally {
            executing = false;
        }
    }

    public boolean isExecuting() {
        return executing;
    }

    private synchronized void drainQueue(Queue<TimeController.Task> taskQueue, Queue<CompletableFuture<Void>> resQueue) {
        while (!taskQueue.isEmpty()) {
            TimeController.Task task = taskQueue.poll();
            CompletableFuture<Void> taskFut = task.execute();
            if (taskFut.isDone()) {
                resQueue.add(taskFut);
            } else {
                CompletableFuture<Void> resFut = taskFut.whenComplete((v, t) -> {
                    drainQueue(taskQueue, resQueue);
                });
                resQueue.add(resFut);
            }
        }
    }
}
