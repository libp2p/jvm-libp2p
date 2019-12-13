// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package javax.jmdns.impl;

import java.io.IOException;
import java.net.DatagramPacket;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.jmdns.impl.constants.DNSConstants;

/**
 * Listen for multicast packets.
 */
class SocketListener extends Thread {
    static Logger           logger = LogManager.getLogger(SocketListener.class.getName());

    /**
     *
     */
    private final JmDNSImpl _jmDNSImpl;

    /**
     * @param jmDNSImpl
     */
    SocketListener(JmDNSImpl jmDNSImpl) {
        super("SocketListener(" + (jmDNSImpl != null ? jmDNSImpl.getName() : "") + ")");
        this.setDaemon(true);
        this._jmDNSImpl = jmDNSImpl;
    }

    @Override
    public void run() {
        try {
            byte buf[] = new byte[DNSConstants.MAX_MSG_ABSOLUTE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (true) {
                packet.setLength(buf.length);
                this._jmDNSImpl.getSocket().receive(packet);
                try {
                    if (this._jmDNSImpl.getLocalHost().shouldIgnorePacket(packet)) {
                        continue;
                    }

                    DNSIncoming msg = new DNSIncoming(packet);
                    if (msg.isValidResponseCode()) {
                        if (logger.isTraceEnabled()) {
                            logger.trace( "{}.run() JmDNS in:{}", this.getName(), msg.print(true));
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
                            logger.debug("{}.run() JmDNS in message with error code: {}", this.getName(), msg.print(true));
                        }
                    }
                } catch (IOException e) {
                    logger.warn(this.getName() + ".run() exception ", e);
                }
            }
        } catch (IOException e) {
            logger.warn(this.getName() + ".run() exception ", e);
        }
        logger.trace("{}.run() exiting.", this.getName() );
    }

    public JmDNSImpl getDns() {
        return _jmDNSImpl;
    }

}
