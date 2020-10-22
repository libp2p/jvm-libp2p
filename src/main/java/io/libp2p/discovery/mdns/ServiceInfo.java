// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL
package io.libp2p.discovery.mdns;

import io.libp2p.discovery.mdns.impl.ServiceInfoImpl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * The fully qualified service name is build using up to 5 components with the following structure:
 * 
 * <pre>
 *            &lt;app&gt;.&lt;protocol&gt;.&lt;servicedomain&gt;.&lt;parentdomain&gt;.<br/>
 * &lt;Instance&gt;.&lt;app&gt;.&lt;protocol&gt;.&lt;servicedomain&gt;.&lt;parentdomain&gt;.<br/>
 * &lt;sub&gt;._sub.&lt;app&gt;.&lt;protocol&gt;.&lt;servicedomain&gt;.&lt;parentdomain&gt;.
 * </pre>
 * 
 * <ol>
 * <li>&lt;servicedomain&gt;.&lt;parentdomain&gt;: This is the domain scope of the service typically "local.", but this can also be something similar to "in-addr.arpa." or "ip6.arpa."</li>
 * <li>&lt;protocol&gt;: This is either "_tcp" or "_udp"</li>
 * <li>&lt;app&gt;: This define the application protocol. Typical example are "_http", "_ftp", etc.</li>
 * <li>&lt;Instance&gt;: This is the service name</li>
 * <li>&lt;sub&gt;: This is the subtype for the application protocol</li>
 * </ol>
 * </p>
 */
public abstract class ServiceInfo implements Cloneable {

    /**
     * Fields for the fully qualified map.
     */
    public enum Fields {
        /**
         * Domain Field.
         */
        Domain,
        /**
         * Protocol Field.
         */
        Protocol,
        /**
         * Application Field.
         */
        Application,
        /**
         * Instance Field.
         */
        Instance,
        /**
         * Subtype Field.
         */
        Subtype
    }

    /**
     * Construct a service description for registering with JmDNS.
     * 
     * @param type
     *            fully qualified service type name, such as <code>_http._tcp.local.</code>.
     * @param name
     *            unqualified service instance name, such as <code>foobar</code>
     * @param port
     *            the local port on which the service runs
     * @param text
     *            string describing the service
     * @return new service info
     */
    public static ServiceInfo create(
            final String type,
            final String name,
            final int port,
            final String text,
            final List<Inet4Address> ip4Addresses,
            final List<Inet6Address> ip6Addresses) {
        ServiceInfoImpl si = new ServiceInfoImpl(type, name, "", port, 0, 0, text);
        for (Inet4Address a : ip4Addresses)
            si.addAddress(a);
        for (Inet6Address a : ip6Addresses)
            si.addAddress(a);
        return si;
    }

    /**
     * Returns true if the service info is filled with data.
     * 
     * @return <code>true</code> if the service info has data, <code>false</code> otherwise.
     */
    public abstract boolean hasData();

    /**
     * Fully qualified service type name, such as <code>_http._tcp.local.</code>
     * 
     * @return service type name
     */
    public abstract String getType();

    /**
     * Fully qualified service type name with the subtype if appropriate, such as <code>_printer._sub._http._tcp.local.</code>
     * 
     * @return service type name
     */
    public abstract String getTypeWithSubtype();

    /**
     * Unqualified service instance name, such as <code>foobar</code> .
     * 
     * @return service name
     */
    public abstract String getName();

    /**
     * The key is used to retrieve service info in hash tables.<br/>
     * The key is the lower case qualified name.
     * 
     * @return the key
     */
    public abstract String getKey();

    /**
     * Fully qualified service name, such as <code>foobar._http._tcp.local.</code> .
     * 
     * @return qualified service name
     */
    public abstract String getQualifiedName();

    /**
     * Get the name of the server.
     * 
     * @return server name
     */
    public abstract String getServer();

    /**
     * Returns a list of all IPv4 InetAddresses that can be used for this service.
     * <p>
     * In a multi-homed environment service info can be associated with more than one address.
     * </p>
     * 
     * @return list of InetAddress objects
     */
    public abstract Inet4Address[] getInet4Addresses();

    /**
     * Returns a list of all IPv6 InetAddresses that can be used for this service.
     * <p>
     * In a multi-homed environment service info can be associated with more than one address.
     * </p>
     * 
     * @return list of InetAddress objects
     */
    public abstract Inet6Address[] getInet6Addresses();

    /**
     * Get the port for the service.
     * 
     * @return service port
     */
    public abstract int getPort();

    /**
     * Get the priority of the service.
     * 
     * @return service priority
     */
    public abstract int getPriority();

    /**
     * Get the weight of the service.
     * 
     * @return service weight
     */
    public abstract int getWeight();

    /**
     * Get the text for the service as raw bytes.
     * 
     * @return raw service text
     */
    public abstract byte[] getTextBytes();

    /**
     * Returns the domain of the service info suitable for printing.
     * 
     * @return service domain
     */
    public abstract String getDomain();

    /**
     * Returns the protocol of the service info suitable for printing.
     * 
     * @return service protocol
     */
    public abstract String getProtocol();

    /**
     * Returns the application of the service info suitable for printing.
     * 
     * @return service application
     */
    public abstract String getApplication();

    /**
     * Returns the sub type of the service info suitable for printing.
     * 
     * @return service sub type
     */
    public abstract String getSubtype();

    /**
     * Returns a dictionary of the fully qualified name component of this service.
     * 
     * @return dictionary of the fully qualified name components
     */
    public abstract Map<Fields, String> getQualifiedNameMap();

    /*
     * (non-Javadoc)
     * @see java.lang.Object#clone()
     */
    @Override
    public ServiceInfo clone() throws CloneNotSupportedException {
        return (ServiceInfo) super.clone();
    }
}
