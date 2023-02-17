// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import io.libp2p.discovery.mdns.impl.DNSOutgoing.MessageOutputStream;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordClass;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType;
import io.libp2p.discovery.mdns.impl.util.ByteWrangler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;


/**
 * DNS record
 *
 * @author Arthur van Hoff, Rick Blair, Werner Randelshofer, Pierre Frisch
 */
public abstract class DNSRecord extends DNSEntry {
    private static Logger logger = LogManager.getLogger(DNSRecord.class.getName());

    private int           _ttl;
    private long          _created;

    /**
     * Create a DNSRecord with a name, type, class, and ttl.
     */
    DNSRecord(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl) {
        super(name, type, recordClass, unique);
        this._ttl = ttl;
        this._created = System.currentTimeMillis();
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof DNSRecord) && super.equals(other) && sameValue((DNSRecord) other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), _ttl, _created);
    }

    abstract boolean sameValue(DNSRecord other);

    /**
     * Get the expiration time of this record.
     */
    long getExpirationTime(int percent) {
        // ttl is in seconds the constant 10 is 1000 ms / 100 %
        return _created + (percent * ((long)_ttl) * 10L);
    }

    /**
     * Get the remaining TTL for this record.
     */
    int getRemainingTTL(long now) {
        return (int) Math.max(0, (getExpirationTime(100) - now) / 1000);
    }

    @Override
    public boolean isExpired(long now) {
        return getExpirationTime(100) <= now;
    }

    abstract void write(MessageOutputStream out);

    public static class IPv4Address extends Address {
        IPv4Address(String name, DNSRecordClass recordClass, boolean unique, int ttl, InetAddress addr) {
            super(name, DNSRecordType.TYPE_A, recordClass, unique, ttl, addr);
        }

        IPv4Address(String name, DNSRecordClass recordClass, boolean unique, int ttl, byte[] rawAddress) {
            super(name, DNSRecordType.TYPE_A, recordClass, unique, ttl, rawAddress);
        }

        @Override
        void write(MessageOutputStream out) {
            if (_addr != null) {
                byte[] buffer = _addr.getAddress();
                // If we have a type A records we should answer with a IPv4 address
                if (_addr instanceof Inet4Address) {
                    // All is good
                } else {
                    // Get the last four bytes
                    byte[] tempbuffer = buffer;
                    buffer = new byte[4];
                    System.arraycopy(tempbuffer, 12, buffer, 0, 4);
                }
                int length = buffer.length;
                out.writeBytes(buffer, 0, length);
            }
        }
    }

    public static class IPv6Address extends Address {
        IPv6Address(String name, DNSRecordClass recordClass, boolean unique, int ttl, InetAddress addr) {
            super(name, DNSRecordType.TYPE_AAAA, recordClass, unique, ttl, addr);
        }

        IPv6Address(String name, DNSRecordClass recordClass, boolean unique, int ttl, byte[] rawAddress) {
            super(name, DNSRecordType.TYPE_AAAA, recordClass, unique, ttl, rawAddress);
        }

        @Override
        void write(MessageOutputStream out) {
            if (_addr != null) {
                byte[] buffer = _addr.getAddress();
                // If we have a type AAAA records we should answer with a IPv6 address
                if (_addr instanceof Inet4Address) {
                    byte[] tempbuffer = buffer;
                    buffer = new byte[16];
                    for (int i = 0; i < 16; i++) {
                        if (i < 11) {
                            buffer[i] = tempbuffer[i - 12];
                        } else {
                            buffer[i] = 0;
                        }
                    }
                }
                int length = buffer.length;
                out.writeBytes(buffer, 0, length);
            }
        }
    }

    /**
     * Address record.
     */
    public static abstract class Address extends DNSRecord {
        InetAddress           _addr;

        protected Address(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl, InetAddress addr) {
            super(name, type, recordClass, unique, ttl);
            this._addr = addr;
        }

        protected Address(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl, byte[] rawAddress) {
            super(name, type, recordClass, unique, ttl);
            try {
                this._addr = InetAddress.getByAddress(rawAddress);
            } catch (UnknownHostException exception) {
                logger.warn("Address() exception ", exception);
            }
        }

        @Override
        boolean sameValue(DNSRecord other) {
            try {
                if (!(other instanceof Address)) {
                    return false;
                }
                Address address = (Address) other;
                if ((this.getAddress() == null) && (address.getAddress() != null)) {
                    return false;
                }
                return this.getAddress().equals(address.getAddress());
            } catch (Exception e) {
                logger.info("Failed to compare addresses of DNSRecords", e);
                return false;
            }
        }

        public InetAddress getAddress() {
            return _addr;
        }

        /**
         * Creates a byte array representation of this record. This is needed for tie-break tests according to draft-cheshire-dnsext-multicastdns-04.txt chapter 9.2.
         */
        @Override
        protected void toByteArray(DataOutputStream dout) throws IOException {
            super.toByteArray(dout);
            byte[] buffer = this.getAddress().getAddress();
            for (int i = 0; i < buffer.length; i++) {
                dout.writeByte(buffer[i]);
            }
        }
    }

    /**
     * Pointer record.
     */
    public static class Pointer extends DNSRecord {
        private final String _alias;

        public Pointer(String name, DNSRecordClass recordClass, boolean unique, int ttl, String alias) {
            super(name, DNSRecordType.TYPE_PTR, recordClass, unique, ttl);
            this._alias = alias;
        }

        @Override
        public boolean isSameEntry(DNSEntry entry) {
            return super.isSameEntry(entry) && (entry instanceof Pointer) && this.sameValue((Pointer) entry);
        }

        @Override
        void write(MessageOutputStream out) {
            out.writeName(_alias);
        }

        @Override
        boolean sameValue(DNSRecord other) {
            if (!(other instanceof Pointer)) {
                return false;
            }
            Pointer pointer = (Pointer) other;
            if ((_alias == null) && (pointer._alias != null)) {
                return false;
            }
            return _alias.equals(pointer._alias);
        }
    }

    public static class Text extends DNSRecord {
        private final byte[] _text;

        public Text(String name, DNSRecordClass recordClass, boolean unique, int ttl, byte text[]) {
            super(name, DNSRecordType.TYPE_TXT, recordClass, unique, ttl);
            this._text = (text != null && text.length > 0 ? text : ByteWrangler.EMPTY_TXT);
        }

        /**
         * @return the text
         */
        public byte[] getText() {
            return this._text;
        }

        @Override
        void write(MessageOutputStream out) {
            out.writeBytes(_text, 0, _text.length);
        }

        @Override
        boolean sameValue(DNSRecord other) {
            if (!(other instanceof Text)) {
                return false;
            }
            Text txt = (Text) other;
            if ((_text == null) && (txt._text != null)) {
                return false;
            }
            if (txt._text.length != _text.length) {
                return false;
            }
            for (int i = _text.length; i-- > 0;) {
                if (txt._text[i] != _text[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Service record.
     */
    public static class Service extends DNSRecord {
        private final int     _priority;
        private final int     _weight;
        private final int     _port;
        private final String  _server;

        public Service(String name, DNSRecordClass recordClass, boolean unique, int ttl, int priority, int weight, int port, String server) {
            super(name, DNSRecordType.TYPE_SRV, recordClass, unique, ttl);
            this._priority = priority;
            this._weight = weight;
            this._port = port;
            this._server = server;
        }

        @Override
        void write(MessageOutputStream out) {
            out.writeShort(_priority);
            out.writeShort(_weight);
            out.writeShort(_port);
            if (DNSIncoming.USE_DOMAIN_NAME_FORMAT_FOR_SRV_TARGET) {
                out.writeName(_server);
            } else {
                // [PJYF Nov 13 2010] Do we still need this? This looks really bad. All label are supposed to start by a length.
                out.writeUTF(_server, 0, _server.length());

                // add a zero byte to the end just to be safe, this is the strange form
                // used by the BonjourConformanceTest
                out.writeByte(0);
            }
        }

        @Override
        protected void toByteArray(DataOutputStream dout) throws IOException {
            super.toByteArray(dout);
            dout.writeShort(_priority);
            dout.writeShort(_weight);
            dout.writeShort(_port);
            try {
                dout.write(_server.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException exception) {
                /* UTF-8 is always present */
            }
        }

        /**
         * @return the weight
         */
        public int getWeight() {
            return this._weight;
        }

        /**
         * @return the port
         */
        public int getPort() {
            return this._port;
        }

        @Override
        boolean sameValue(DNSRecord other) {
            if (!(other instanceof Service)) {
                return false;
            }
            Service s = (Service) other;
            return (_priority == s._priority) && (_weight == s._weight) && (_port == s._port) && _server.equals(s._server);
        }
    }

    public static class HostInformation extends DNSRecord {
        String _os;
        String _cpu;

        /**
         * @param name
         * @param recordClass
         * @param unique
         * @param ttl
         * @param cpu
         * @param os
         */
        public HostInformation(String name, DNSRecordClass recordClass, boolean unique, int ttl, String cpu, String os) {
            super(name, DNSRecordType.TYPE_HINFO, recordClass, unique, ttl);
            _cpu = cpu;
            _os = os;
        }

        /*
         * (non-Javadoc)
         * @see javax.jmdns.impl.DNSRecord#sameValue(javax.jmdns.impl.DNSRecord)
         */
        @Override
        boolean sameValue(DNSRecord other) {
            if (!(other instanceof HostInformation)) {
                return false;
            }
            HostInformation hinfo = (HostInformation) other;
            if ((_cpu == null) && (hinfo._cpu != null)) {
                return false;
            }
            if ((_os == null) && (hinfo._os != null)) {
                return false;
            }
            return _cpu.equals(hinfo._cpu) && _os.equals(hinfo._os);
        }

        @Override
        void write(MessageOutputStream out) {
            String hostInfo = _cpu + " " + _os;
            out.writeUTF(hostInfo, 0, hostInfo.length());
        }
    }

    public int getTTL() {
        return _ttl;
    }
}
