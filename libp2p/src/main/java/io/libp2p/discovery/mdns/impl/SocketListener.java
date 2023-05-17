// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.libp2p.discovery.mdns.impl.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.libp2p.discovery.mdns.impl.constants.DNSConstants;

/**
 * Listen for multicast packets.
 */
class SocketListener implements Runnable {
    static Logger logger = LoggerFactory.getLogger(SocketListener.class.getName());

    private final JmDNSImpl _jmDNSImpl;
    private final String _name;
    private volatile boolean _closed;
    private final ExecutorService _executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("JmDNS"));
    private Future<Void> _isShutdown;

    SocketListener(JmDNSImpl jmDNSImpl) {
        _name = "SocketListener(" + (jmDNSImpl != null ? jmDNSImpl.getName() : "") + ")";
        this._jmDNSImpl = jmDNSImpl;
    }

    public void start() {
        _isShutdown = _executor.submit(this, null);
    }
    public Future<Void> stop() {
        _closed = true;
        _executor.shutdown();
        return _isShutdown;
    }

    @Override
    public void run() {
        try {
            byte buf[] = new byte[DNSConstants.MAX_MSG_ABSOLUTE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (!_closed) {
                packet.setLength(buf.length);
                this._jmDNSImpl.getSocket().receive(packet);
                if (_closed)
                    break;
                try {
                    if (this._jmDNSImpl.getLocalHost().shouldIgnorePacket(packet)) {
                        continue;
                    }

                    DNSIncoming msg = new DNSIncoming(packet);
                    if (msg.isValidResponseCode()) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("{}.run() JmDNS in:{}", _name, msg.print(true));
                        }
                        if (msg.isQuery()) {
                            if (packet.getPort() != DNSConstants.MDNS_PORT) {
                                this._jmDNSImpl.handleQuery(msg, packet.getAddress(), packet.getPort());
                            }
                            this._jmDNSImpl.handleQuery(msg, this._jmDNSImpl.getGroup(), DNSConstants.MDNS_PORT);
                        } else {
                            this._jmDNSImpl.handleResponse(msg);
                        }
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}.run() JmDNS in message with error code: {}", _name, msg.print(true));
                        }
                    }
                } catch (IOException e) {
                    logger.warn(_name + ".run() exception ", e);
                }
            }
        } catch (IOException e) {
            if (!_closed)
                logger.warn(_name + ".run() exception ", e);
        }
        logger.trace("{}.run() exiting.", _name);
    }
}
