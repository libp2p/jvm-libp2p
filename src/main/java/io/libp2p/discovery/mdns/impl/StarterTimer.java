package io.libp2p.discovery.mdns.impl;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class StarterTimer extends Timer {
    private volatile boolean _cancelled;

    public StarterTimer(String name, boolean isDaemon) {
        super(name, isDaemon);
        _cancelled = false;
    }

    @Override
    public synchronized void cancel() {
        if (_cancelled) return;
        _cancelled = true;
        super.cancel();
    }

    @Override
    public synchronized void schedule(TimerTask task, long delay) {
        if (_cancelled) return;
        super.schedule(task, delay);
    }

    @Override
    public synchronized void schedule(TimerTask task, Date time) {
        if (_cancelled) return;
        super.schedule(task, time);
    }

    @Override
    public synchronized void schedule(TimerTask task, long delay, long period) {
        if (_cancelled) return;
        super.schedule(task, delay, period);
    }

    @Override
    public synchronized void schedule(TimerTask task, Date firstTime, long period) {
        if (_cancelled) return;
        super.schedule(task, firstTime, period);
    }

    @Override
    public synchronized void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        if (_cancelled) return;
        super.scheduleAtFixedRate(task, delay, period);
    }

    @Override
    public synchronized void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) {
        if (_cancelled) return;
        super.scheduleAtFixedRate(task, firstTime, period);
    }
}
