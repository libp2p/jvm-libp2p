// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.libp2p.discovery.mdns.impl.util.NamedThreadFactory;

import io.libp2p.discovery.mdns.impl.constants.DNSConstants;

/**
 * Listen for multicast packets.
 */
class SocketListener implements Runnable {
    static Logger logger = Logger.getLogger(SocketListener.class.getName());

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
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.log(Level.FINEST, "{}.run() JmDNS in:{}", new Object[] {_name, msg.print(true)});
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
                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE, "{}.run() JmDNS in message with error code: {}", new Object[] {_name, msg.print(true)});
                        }
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, _name + ".run() exception ", e);
                }
            }
        } catch (IOException e) {
            if (!_closed)
                logger.log(Level.WARNING, _name + ".run() exception ", e);
        }
        logger.log(Level.FINEST, "{}.run() exiting.", _name);
    }
}
