// /Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns;

import io.libp2p.discovery.mdns.impl.JmDNSImpl;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Based on code by Arthur van Hoff, Rick Blair, Jeff Sonstein, Werner Randelshofer, Pierre Frisch, Scott Lewis, Scott Cytacki
 */
public abstract class JmDNS {
    /**
     * <p>
     * Create an instance of JmDNS and bind it to a specific network interface given its IP-address.
     * </p>
     * <p>
     * <b>Note:</b> This is a convenience method. The preferred constructor is {@link #create(InetAddress, String)}.<br/>
     * Check that your platform correctly handle the default localhost IP address and the local hostname. In doubt use the explicit constructor.<br/>
     * This call is equivalent to <code>create(addr, null)</code>.
     * </p>
     *
     * @see #create(InetAddress, String)
     * @param addr
     *            IP address to bind to.
     * @return jmDNS instance
     */
    public static JmDNS create(final InetAddress addr) {
        return new JmDNSImpl(addr, null);
    }

    /**
     * <p>
     * Create an instance of JmDNS and bind it to a specific network interface given its IP-address.
     * </p>
     * If <code>addr</code> parameter is null this method will try to resolve to a local IP address of the machine using a network discovery:
     * <ol>
     * <li>Check the system property <code>net.mdns.interface</code></li>
     * <li>Check the JVM local host</li>
     * <li>In the last resort bind to the loopback address. This is non functional in most cases.</li>
     * </ol>
     * If <code>name</code> parameter is null will use the hostname. The hostname is determined by the following algorithm:
     * <ol>
     * <li>Get the hostname from the InetAdress obtained before.</li>
     * <li>If the hostname is a reverse lookup default to <code>JmDNS name</code> or <code>computer</code> if null.</li>
     * <li>If the name contains <code>'.'</code> replace them by <code>'-'</code></li>
     * <li>Add <code>.local.</code> at the end of the name.</li>
     * </ol>
     *
     * @param addr
     *            IP address to bind to.
     * @param name
     *            name of the newly created JmDNS
     * @return jmDNS instance
     * @exception IOException
     *                if an exception occurs during the socket creation
     */
    public static JmDNS create(final InetAddress addr, final String name) {
        return new JmDNSImpl(addr, name);
    }

    public abstract void start() throws IOException;
    public abstract void stop();

    /**
     * Return the name of the JmDNS instance. This is an arbitrary string that is useful for distinguishing instances.
     *
     * @return name of the JmDNS
     */
    public abstract String getName();

    public abstract void addAnswerListener(String type, AnswerListener listener);
    public abstract void registerService(ServiceInfo info) throws IOException;
}
