// /Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package javax.jmdns;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;

import javax.jmdns.impl.JmDNSImpl;

/**
 * mDNS implementation in Java.
 *
 * @author Arthur van Hoff, Rick Blair, Jeff Sonstein, Werner Randelshofer, Pierre Frisch, Scott Lewis, Scott Cytacki
 */
public abstract class JmDNS implements Closeable {
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
     * @exception IOException
     *                if an exception occurs during the socket creation
     */
    public static JmDNS create(final InetAddress addr) throws IOException {
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
    public static JmDNS create(final InetAddress addr, final String name) throws IOException {
        return new JmDNSImpl(addr, name);
    }

    /**
     * Return the name of the JmDNS instance. This is an arbitrary string that is useful for distinguishing instances.
     *
     * @return name of the JmDNS
     */
    public abstract String getName();

    /**
     * Return the HostName associated with this JmDNS instance. Note: May not be the same as what started. The host name is subject to negotiation.
     *
     * @return Host name
     */
    public abstract String getHostName();

    /**
     * Return the address of the interface to which this instance of JmDNS is bound.
     *
     * @return Internet Address
     * @exception IOException
     *                if there is an error in the underlying protocol, such as a TCP error.
     */
    public abstract InetAddress getInetAddress() throws IOException;

    public abstract void addAnswerListener(String type, AnswerListener listener);

    public abstract void startServiceResolver(String type);

        /**
         * Register a service. The service is registered for access by other jmdns clients. The name of the service may be changed to make it unique.<br>
         * Note that the given {@code ServiceInfo} is bound to this {@code JmDNS} instance, and should not be reused for any other {@linkplain #registerService(ServiceInfo)}.
         *
         * @param info
         *            service info to register
         * @exception IOException
         *                if there is an error in the underlying protocol, such as a TCP error.
         */
    public abstract void registerService(ServiceInfo info) throws IOException;

    /**
     * Unregister all services.
     */
    public abstract void unregisterAllServices();
}
