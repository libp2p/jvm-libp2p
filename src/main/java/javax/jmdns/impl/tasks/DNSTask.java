// Licensed under Apache License version 2.0
package javax.jmdns.impl.tasks;

import java.io.IOException;
import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.jmdns.impl.DNSOutgoing;
import javax.jmdns.impl.DNSQuestion;
import javax.jmdns.impl.JmDNSImpl;
import javax.jmdns.impl.constants.DNSConstants;

public abstract class DNSTask implements Runnable {
    private final JmDNSImpl _jmDNSImpl;
    protected final ScheduledExecutorService _scheduler =
            Executors.newScheduledThreadPool(1);

    protected DNSTask(JmDNSImpl jmDNSImpl) {
        super();
        this._jmDNSImpl = jmDNSImpl;
    }

    protected JmDNSImpl dns() {
        return _jmDNSImpl;
    }

    public abstract void start();

    protected abstract String getName();

    @Override
    public String toString() {
        return this.getName();
    }

    protected DNSOutgoing addQuestion(DNSOutgoing out, DNSQuestion rec) throws IOException {
        DNSOutgoing newOut = out;
        try {
            newOut.addQuestion(rec);
        } catch (final IOException e) {
            int flags = newOut.getFlags();
            boolean multicast = newOut.isMulticast();
            int maxUDPPayload = newOut.getMaxUDPPayload();
            int id = newOut.getId();

            newOut.setFlags(flags | DNSConstants.FLAGS_TC);
            newOut.setId(id);
            this._jmDNSImpl.send(newOut);

            newOut = new DNSOutgoing(flags, multicast, maxUDPPayload);
            newOut.addQuestion(rec);
        }
        return newOut;
    }
}
