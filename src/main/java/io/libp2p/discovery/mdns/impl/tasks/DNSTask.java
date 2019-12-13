// Licensed under Apache License version 2.0
package io.libp2p.discovery.mdns.impl.tasks;

import io.libp2p.discovery.mdns.impl.DNSOutgoing;
import io.libp2p.discovery.mdns.impl.DNSQuestion;
import io.libp2p.discovery.mdns.impl.JmDNSImpl;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.libp2p.discovery.mdns.impl.constants.DNSConstants;

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
