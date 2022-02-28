// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import java.io.IOException;
import java.net.*;
import java.util.*;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * HostInfo information on the local host to be able to cope with change of addresses.
 *
 * @author Pierre Frisch, Werner Randelshofer
 */
public class HostInfo {
    private static Logger       logger = LogManager.getLogger(HostInfo.class.getName());

    protected String            _name;

    protected InetAddress       _address;

    protected NetworkInterface  _interface;

    /**
     * @param address
     *            IP address to bind
     * @param dns
     *            JmDNS instance
     * @param jmdnsName
     *            JmDNS name
     * @return new HostInfo
     */
    public static HostInfo newHostInfo(InetAddress address, JmDNSImpl dns, String jmdnsName) {
        HostInfo localhost = null;
        String aName = (jmdnsName != null ? jmdnsName : "");
        InetAddress addr = address;
        try {
            if (addr == null) {
                String ip = System.getProperty("net.mdns.interface");
                if (ip != null) {
                    addr = InetAddress.getByName(ip);
                } else {
                    addr = InetAddress.getLocalHost();
                    if (addr.isLoopbackAddress()) {
                        // Find local address that isn't a loopback address
                        InetAddress[] addresses = getInetAddresses();
                        if (addresses.length > 0) {
                            addr = addresses[0];
                        }
                    }
                }
                if (addr.isLoopbackAddress()) {
                    logger.warn("Could not find any address beside the loopback.");
                }
            }
            if (aName.length() == 0) {
                aName = addr.getHostName();
            }
            if (aName.contains("in-addr.arpa") || (aName.equals(addr.getHostAddress()))) {
                aName = ((jmdnsName != null) && (jmdnsName.length() > 0) ? jmdnsName : addr.getHostAddress());
            }
        } catch (final IOException e) {
            logger.warn("Could not initialize the host network interface on " + address + "because of an error: " + e.getMessage(), e);
            // This is only used for running unit test on Debian / Ubuntu
            addr = loopbackAddress();
            aName = ((jmdnsName != null) && (jmdnsName.length() > 0) ? jmdnsName : "computer");
        }
        // A host name with "." is illegal. so strip off everything and append .local.
        // We also need to be carefull that the .local may already be there
        int index = aName.indexOf(".local");
        if (index > 0) {
            aName = aName.substring(0, index);
        }
        aName = aName.replaceAll("[:%\\.]", "-");
        aName += ".local.";
        localhost = new HostInfo(addr, aName, dns);
        return localhost;
    }

    private static InetAddress[] getInetAddresses() {
        Set<InetAddress> result = new HashSet<InetAddress>();
        try {

            for (Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces(); nifs.hasMoreElements();) {
                NetworkInterface nif = nifs.nextElement();
                if (useInterface(nif)) {
                    for (Enumeration<InetAddress> iaenum = nif.getInetAddresses(); iaenum.hasMoreElements();) {
                        InetAddress interfaceAddress = iaenum.nextElement();
                        logger.trace("Found NetworkInterface/InetAddress: {} -- {}",  nif , interfaceAddress);
                        result.add(interfaceAddress);
                    }
                }
            }
        } catch (SocketException se) {
            logger.warn("Error while fetching network interfaces addresses: " + se);
        }
        return result.toArray(new InetAddress[result.size()]);
    }

    private static boolean useInterface(NetworkInterface networkInterface) {
        try {
            if (!networkInterface.isUp()) {
                return false;
            }

            if (!networkInterface.supportsMulticast()) {
                return false;
            }

            if (networkInterface.isLoopback()) {
                return false;
            }

            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static InetAddress loopbackAddress() {
        try {
            return InetAddress.getByName(null);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private HostInfo(final InetAddress address, final String name, final JmDNSImpl dns) {
        super();
        this._address = address;
        this._name = name;
        if (address != null) {
            try {
                _interface = NetworkInterface.getByInetAddress(address);
            } catch (Exception exception) {
                logger.warn("LocalHostInfo() exception ", exception);
            }
        }
    }

    public String getName() {
        return _name;
    }

    public InetAddress getInetAddress() {
        return _address;
    }

    public NetworkInterface getInterface() {
        return _interface;
    }

    boolean shouldIgnorePacket(DatagramPacket packet) {
        boolean result = false;
        if (this.getInetAddress() != null) {
            InetAddress from = packet.getAddress();
            if (from != null) {
                if ((this.getInetAddress().isLinkLocalAddress() || this.getInetAddress().isMCLinkLocal()) && (!from.isLinkLocalAddress())) {
                    // A host sending Multicast DNS queries to a link-local destination
                    // address (including the 224.0.0.251 and FF02::FB link-local multicast
                    // addresses) MUST only accept responses to that query that originate
                    // from the local link, and silently discard any other response packets.
                    // Without this check, it could be possible for remote rogue hosts to
                    // send spoof answer packets (perhaps unicast to the victim host) which
                    // the receiving machine could misinterpret as having originated on the
                    // local link.
                    result = true;
                }
                // if (from.isLinkLocalAddress() && (!this.getInetAddress().isLinkLocalAddress())) {
                // // Ignore linklocal packets on regular interfaces, unless this is
                // // also a linklocal interface. This is to avoid duplicates. This is
                // // a terrible hack caused by the lack of an API to get the address
                // // of the interface on which the packet was received.
                // result = true;
                // }
                if (from.isLoopbackAddress() && (!this.getInetAddress().isLoopbackAddress())) {
                    // Ignore loopback packets on a regular interface unless this is also a loopback interface.
                    result = true;
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("local host info[");
        sb.append(getName() != null ? getName() : "no name");
        sb.append(", ");
        sb.append(getInterface() != null ? getInterface().getDisplayName() : "???");
        sb.append(":");
        sb.append(getInetAddress() != null ? getInetAddress().getHostAddress() : "no address");
        sb.append("]");
        return sb.toString();
    }
}
