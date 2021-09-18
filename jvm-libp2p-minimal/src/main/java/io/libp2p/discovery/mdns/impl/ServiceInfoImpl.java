// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import io.libp2p.discovery.mdns.ServiceInfo;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordClass;
import io.libp2p.discovery.mdns.impl.util.ByteWrangler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JmDNS service information.
 *
 * @author Arthur van Hoff, Jeff Sonstein, Werner Randelshofer, Victor Toni
 */
public class ServiceInfoImpl extends ServiceInfo {
    private static Logger           logger = LogManager.getLogger(ServiceInfoImpl.class.getName());

    private String                  _domain;
    private String                  _protocol;
    private String                  _application;
    private String                  _name;
    private String                  _subtype;
    private String                  _server;
    private int                     _port;
    private int                     _weight;
    private int                     _priority;
    private byte[]                  _text;
    private final Set<Inet4Address> _ipv4Addresses;
    private final Set<Inet6Address> _ipv6Addresses;

    private transient String        _key;

    /**
     * @param type
     * @param name
     * @param subtype
     * @param port
     * @param weight
     * @param priority
     * @param text
     */
    public ServiceInfoImpl(String type, String name, String subtype, int port, int weight, int priority, String text) {
        this(ServiceInfoImpl.decodeQualifiedNameMap(type, name, subtype), port, weight, priority, (byte[]) null);

        try {
            this._text = ByteWrangler.encodeText(text);
        } catch (final IOException e) {
            throw new RuntimeException("Unexpected exception: " + e);
        }

        _server = text;
    }

    ServiceInfoImpl(Map<Fields, String> qualifiedNameMap, int port, int weight, int priority, byte text[]) {
        Map<Fields, String> map = ServiceInfoImpl.checkQualifiedNameMap(qualifiedNameMap);

        this._domain = map.get(Fields.Domain);
        this._protocol = map.get(Fields.Protocol);
        this._application = map.get(Fields.Application);
        this._name = map.get(Fields.Instance);
        this._subtype = map.get(Fields.Subtype);

        this._port = port;
        this._weight = weight;
        this._priority = priority;
        this._text = text;
        this._ipv4Addresses = Collections.synchronizedSet(new LinkedHashSet<Inet4Address>());
        this._ipv6Addresses = Collections.synchronizedSet(new LinkedHashSet<Inet6Address>());
    }

    ServiceInfoImpl(ServiceInfo info) {
        this._ipv4Addresses = Collections.synchronizedSet(new LinkedHashSet<Inet4Address>());
        this._ipv6Addresses = Collections.synchronizedSet(new LinkedHashSet<Inet6Address>());
        if (info != null) {
            this._domain = info.getDomain();
            this._protocol = info.getProtocol();
            this._application = info.getApplication();
            this._name = info.getName();
            this._subtype = info.getSubtype();
            this._port = info.getPort();
            this._weight = info.getWeight();
            this._priority = info.getPriority();
            this._text = info.getTextBytes();
            Inet6Address[] ipv6Addresses = info.getInet6Addresses();
            for (Inet6Address address : ipv6Addresses) {
                this._ipv6Addresses.add(address);
            }
            Inet4Address[] ipv4Addresses = info.getInet4Addresses();
            for (Inet4Address address : ipv4Addresses) {
                this._ipv4Addresses.add(address);
            }
        }
    }

    public static Map<Fields, String> decodeQualifiedNameMap(String type, String name, String subtype) {
        Map<Fields, String> qualifiedNameMap = decodeQualifiedNameMapForType(type);

        qualifiedNameMap.put(Fields.Instance, name);
        qualifiedNameMap.put(Fields.Subtype, subtype);

        return checkQualifiedNameMap(qualifiedNameMap);
    }

    public static Map<Fields, String> decodeQualifiedNameMapForType(String type) {
        int index;

        String casePreservedType = type;

        String aType = type.toLowerCase();
        String application = aType;
        String protocol = "";
        String subtype = "";
        String name = "";
        String domain = "";

        if (aType.contains("in-addr.arpa") || aType.contains("ip6.arpa")) {
            index = (aType.contains("in-addr.arpa") ? aType.indexOf("in-addr.arpa") : aType.indexOf("ip6.arpa"));
            name = removeSeparators(casePreservedType.substring(0, index));
            domain = casePreservedType.substring(index);
            application = "";
        } else if ((!aType.contains("_")) && aType.contains(".")) {
            index = aType.indexOf('.');
            name = removeSeparators(casePreservedType.substring(0, index));
            domain = removeSeparators(casePreservedType.substring(index));
            application = "";
        } else {
            // First remove the name if it there.
            if (!aType.startsWith("_") || aType.startsWith("_services")) {
                index = aType.indexOf("._");
                if (index > 0) {
                    // We need to preserve the case for the user readable name.
                    name = casePreservedType.substring(0, index);
                    if (index + 1 < aType.length()) {
                        aType = aType.substring(index + 1);
                        casePreservedType = casePreservedType.substring(index + 1);
                    }
                }
            }

            index = aType.lastIndexOf("._");
            if (index > 0) {
                int start = index + 2;
                int end = aType.indexOf('.', start);
                protocol = casePreservedType.substring(start, end);
            }
            if (protocol.length() > 0) {
                index = aType.indexOf("_" + protocol.toLowerCase() + ".");
                int start = index + protocol.length() + 2;
                int end = aType.length() - (aType.endsWith(".") ? 1 : 0);
                if (end > start) {
                    domain = casePreservedType.substring(start, end);
                }
                if (index > 0) {
                    application = casePreservedType.substring(0, index - 1);
                } else {
                    application = "";
                }
            }
            index = application.toLowerCase().indexOf("._sub");
            if (index > 0) {
                int start = index + 5;
                subtype = removeSeparators(application.substring(0, index));
                application = application.substring(start);
            }
        }

        final Map<Fields, String> qualifiedNameMap = new HashMap<Fields, String>(5);
        qualifiedNameMap.put(Fields.Domain, removeSeparators(domain));
        qualifiedNameMap.put(Fields.Protocol, protocol);
        qualifiedNameMap.put(Fields.Application, removeSeparators(application));
        qualifiedNameMap.put(Fields.Instance, name);
        qualifiedNameMap.put(Fields.Subtype, subtype);

        return qualifiedNameMap;
    }

    protected static Map<Fields, String> checkQualifiedNameMap(Map<Fields, String> qualifiedNameMap) {
        Map<Fields, String> checkedQualifiedNameMap = new HashMap<Fields, String>(5);

        // Optional domain
        String domain = (qualifiedNameMap.containsKey(Fields.Domain) ? qualifiedNameMap.get(Fields.Domain) : "local");
        if ((domain == null) || (domain.length() == 0)) {
            domain = "local";
        }
        domain = removeSeparators(domain);
        checkedQualifiedNameMap.put(Fields.Domain, domain);
        // Optional protocol
        String protocol = (qualifiedNameMap.containsKey(Fields.Protocol) ? qualifiedNameMap.get(Fields.Protocol) : "tcp");
        if ((protocol == null) || (protocol.length() == 0)) {
            protocol = "tcp";
        }
        protocol = removeSeparators(protocol);
        checkedQualifiedNameMap.put(Fields.Protocol, protocol);
        // Application
        String application = (qualifiedNameMap.containsKey(Fields.Application) ? qualifiedNameMap.get(Fields.Application) : "");
        if ((application == null) || (application.length() == 0)) {
            application = "";
        }
        application = removeSeparators(application);
        checkedQualifiedNameMap.put(Fields.Application, application);
        // Instance
        String instance = (qualifiedNameMap.containsKey(Fields.Instance) ? qualifiedNameMap.get(Fields.Instance) : "");
        if ((instance == null) || (instance.length() == 0)) {
            instance = "";
            // throw new IllegalArgumentException("The instance name component of a fully qualified service cannot be empty.");
        }
        instance = removeSeparators(instance);
        checkedQualifiedNameMap.put(Fields.Instance, instance);
        // Optional Subtype
        String subtype = (qualifiedNameMap.containsKey(Fields.Subtype) ? qualifiedNameMap.get(Fields.Subtype) : "");
        if ((subtype == null) || (subtype.length() == 0)) {
            subtype = "";
        }
        subtype = removeSeparators(subtype);
        checkedQualifiedNameMap.put(Fields.Subtype, subtype);

        return checkedQualifiedNameMap;
    }

    private static String removeSeparators(String name) {
        if (name == null) {
            return "";
        }
        String newName = name.trim();
        if (newName.startsWith(".")) {
            newName = newName.substring(1);
        }
        if (newName.startsWith("_")) {
            newName = newName.substring(1);
        }
        if (newName.endsWith(".")) {
            newName = newName.substring(0, newName.length() - 1);
        }
        return newName;
    }

    @Override
    public String getType() {
        String domain = this.getDomain();
        String protocol = this.getProtocol();
        String application = this.getApplication();
        return (application.length() > 0 ? "_" + application + "." : "") + (protocol.length() > 0 ? "_" + protocol + "." : "") + domain + ".";
    }

    @Override
    public String getTypeWithSubtype() {
        String subtype = this.getSubtype();
        return (subtype.length() > 0 ? "_" + subtype + "._sub." : "") + this.getType();
    }

    @Override
    public String getName() {
        return (_name != null ? _name : "");
    }

    @Override
    public String getKey() {
        if (this._key == null) {
            this._key = this.getQualifiedName().toLowerCase();
        }
        return this._key;
    }

    @Override
    public String getQualifiedName() {
        String domain = this.getDomain();
        String protocol = this.getProtocol();
        String application = this.getApplication();
        String instance = this.getName();
        return (instance.length() > 0 ? instance + "." : "") + (application.length() > 0 ? "_" + application + "." : "") + (protocol.length() > 0 ? "_" + protocol + "." : "") + domain + ".";
    }

    @Override
    public String getServer() {
        return (_server != null ? _server : "");
    }
    void setServer(String server) {
        this._server = server;
    }

    public void addAddress(Inet4Address addr) {
        _ipv4Addresses.add(addr);
    }
    public void addAddress(Inet6Address addr) {
        _ipv6Addresses.add(addr);
    }

    @Override
    public Inet4Address[] getInet4Addresses() {
        return _ipv4Addresses.toArray(new Inet4Address[_ipv4Addresses.size()]);
    }

    @Override
    public Inet6Address[] getInet6Addresses() {
        return _ipv6Addresses.toArray(new Inet6Address[_ipv6Addresses.size()]);
    }

    @Override
    public int getPort() {
        return _port;
    }

    @Override
    public int getPriority() {
        return _priority;
    }

    @Override
    public int getWeight() {
        return _weight;
    }

    @Override
    public byte[] getTextBytes() {
        return (this._text != null && this._text.length > 0 ? this._text : ByteWrangler.EMPTY_TXT);
    }

    @Override
    public String getApplication() {
        return (_application != null ? _application : "");
    }

    @Override
    public String getDomain() {
        return (_domain != null ? _domain : "local");
    }

    @Override
    public String getProtocol() {
        return (_protocol != null ? _protocol : "tcp");
    }

    @Override
    public String getSubtype() {
        return (_subtype != null ? _subtype : "");
    }

    @Override
    public Map<Fields, String> getQualifiedNameMap() {
        Map<Fields, String> map = new HashMap<Fields, String>(5);

        map.put(Fields.Domain, this.getDomain());
        map.put(Fields.Protocol, this.getProtocol());
        map.put(Fields.Application, this.getApplication());
        map.put(Fields.Instance, this.getName());
        map.put(Fields.Subtype, this.getSubtype());
        return map;
    }

    @Override
    public synchronized boolean hasData() {
        return this.getServer() != null && this.hasInetAddress() && this.getTextBytes() != null && this.getTextBytes().length > 0;
    }

    private boolean hasInetAddress() {
        return _ipv4Addresses.size() > 0 || _ipv6Addresses.size() > 0;
    }

    @Override
    public int hashCode() {
        return getQualifiedName().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof ServiceInfoImpl) && getQualifiedName().equals(((ServiceInfoImpl) obj).getQualifiedName());
    }

    @Override
    public ServiceInfoImpl clone() {
        ServiceInfoImpl serviceInfo = new ServiceInfoImpl(this.getQualifiedNameMap(), _port, _weight, _priority, _text);
        serviceInfo._ipv6Addresses.addAll(Arrays.asList(getInet6Addresses()));
        serviceInfo._ipv4Addresses.addAll(Arrays.asList(getInet4Addresses()));
        serviceInfo._server = _server;
        return serviceInfo;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append('[').append(this.getClass().getSimpleName()).append('@').append(System.identityHashCode(this));
        sb.append(" name: '");
        if (0 < this.getName().length()) {
            sb.append(this.getName()).append('.');
        }
        sb.append(this.getTypeWithSubtype());
        sb.append("' address: '");
        Inet4Address[] addresses4 = this.getInet4Addresses();
        for (InetAddress address : addresses4) {
            sb.append(address).append(':').append(this.getPort()).append(' ');
        }
        Inet6Address[] addresses6 = this.getInet6Addresses();
        for (InetAddress address : addresses6) {
            sb.append(address).append(':').append(this.getPort()).append(' ');
        }

        sb.append(this.hasData() ? " has data" : " has NO data");
        sb.append(']');

        return sb.toString();
    }

    /**
     * Create a series of answer that correspond with the give service info.
     *
     * @param recordClass
     *            record class of the query
     * @param unique
     * @param ttl
     * @param localHost
     * @return collection of answers
     */
    public Collection<DNSRecord> answers(DNSRecordClass recordClass, boolean unique, int ttl, HostInfo localHost) {
        List<DNSRecord> list = new ArrayList<DNSRecord>();

        if ((recordClass == DNSRecordClass.CLASS_ANY) || (recordClass == DNSRecordClass.CLASS_IN)) {
            if (this.getSubtype().length() > 0) {
                list.add(new DNSRecord.Pointer(this.getTypeWithSubtype(), DNSRecordClass.CLASS_IN, DNSRecordClass.NOT_UNIQUE, ttl, this.getQualifiedName()));
            }
            list.add(new DNSRecord.Pointer(this.getType(), DNSRecordClass.CLASS_IN, DNSRecordClass.NOT_UNIQUE, ttl, this.getQualifiedName()));
            list.add(new DNSRecord.Service(this.getQualifiedName(), DNSRecordClass.CLASS_IN, unique, ttl, _priority, _weight, _port, localHost.getName()));
            list.add(new DNSRecord.Text(this.getQualifiedName(), DNSRecordClass.CLASS_IN, unique, ttl, this.getTextBytes()));
            for (InetAddress address : _ipv4Addresses)
                list.add(
                    new DNSRecord.IPv4Address(
                            this.getQualifiedName(),
                            DNSRecordClass.CLASS_IN,
                            unique,
                            ttl,
                            address
                    )
                );
            for (InetAddress address : _ipv6Addresses)
                list.add(
                    new DNSRecord.IPv6Address(
                            this.getQualifiedName(),
                            DNSRecordClass.CLASS_IN,
                            unique,
                            ttl,
                            address
                    )
                );
        }

        return list;
    }
}
