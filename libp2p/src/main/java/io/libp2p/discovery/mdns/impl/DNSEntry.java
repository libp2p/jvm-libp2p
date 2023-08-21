// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import io.libp2p.discovery.mdns.ServiceInfo.Fields;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordClass;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * DNS entry with a name, type, and class. This is the base class for questions and records.
 *
 * @author Arthur van Hoff, Pierre Frisch, Rick Blair
 */
public abstract class DNSEntry {
  // private static Logger logger = LoggerFactory.getLogger(DNSEntry.class.getName());
  private final String _key;

  private final String _name;

  private final String _type;

  private final DNSRecordType _recordType;

  private final DNSRecordClass _dnsClass;

  private final boolean _unique;

  final Map<Fields, String> _qualifiedNameMap;

  /** Create an entry. */
  DNSEntry(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique) {
    _name = name;
    // _key = (name != null ? name.trim().toLowerCase() : null);
    _recordType = type;
    _dnsClass = recordClass;
    _unique = unique;
    _qualifiedNameMap = ServiceInfoImpl.decodeQualifiedNameMapForType(this.getName());
    String domain = _qualifiedNameMap.get(Fields.Domain);
    String protocol = _qualifiedNameMap.get(Fields.Protocol);
    String application = _qualifiedNameMap.get(Fields.Application);
    String instance = _qualifiedNameMap.get(Fields.Instance).toLowerCase();
    _type =
        (application.length() > 0 ? "_" + application + "." : "")
            + (protocol.length() > 0 ? "_" + protocol + "." : "")
            + domain
            + ".";
    _key = ((instance.length() > 0 ? instance + "." : "") + _type).toLowerCase();
  }

  /*
   * (non-Javadoc)
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object obj) {
    boolean result = false;
    if (obj instanceof DNSEntry) {
      DNSEntry other = (DNSEntry) obj;
      result =
          this.getKey().equals(other.getKey())
              && this.getRecordType().equals(other.getRecordType())
              && this.getRecordClass() == other.getRecordClass();
    }
    return result;
  }

  /**
   * Check if two entries have exactly the same name, type, and class.
   *
   * @param entry
   * @return <code>true</code> if the two entries have are for the same record, <code>false</code>
   *     otherwise
   */
  public boolean isSameEntry(DNSEntry entry) {
    return this.getKey().equals(entry.getKey())
        && this.matchRecordType(entry.getRecordType())
        && this.matchRecordClass(entry.getRecordClass());
  }

  /**
   * Check if the requested record class match the current record class
   *
   * @param recordClass
   * @return <code>true</code> if the two entries have compatible class, <code>false</code>
   *     otherwise
   */
  public boolean matchRecordClass(DNSRecordClass recordClass) {
    return (DNSRecordClass.CLASS_ANY == recordClass)
        || (DNSRecordClass.CLASS_ANY == this.getRecordClass())
        || this.getRecordClass().equals(recordClass);
  }

  /**
   * Check if the requested record tyep match the current record type
   *
   * @param recordType
   * @return <code>true</code> if the two entries have compatible type, <code>false</code> otherwise
   */
  public boolean matchRecordType(DNSRecordType recordType) {
    return this.getRecordType().equals(recordType);
  }

  /**
   * Returns the subtype of this entry
   *
   * @return subtype of this entry
   */
  public String getSubtype() {
    String subtype = this.getQualifiedNameMap().get(Fields.Subtype);
    return (subtype != null ? subtype : "");
  }

  /**
   * Returns the name of this entry
   *
   * @return name of this entry
   */
  public String getName() {
    return (_name != null ? _name : "");
  }

  /**
   * @return the type
   */
  public String getType() {
    return (_type != null ? _type : "");
  }

  /**
   * Returns the key for this entry. The key is the lower case name.
   *
   * @return key for this entry
   */
  public String getKey() {
    return (_key != null ? _key : "");
  }

  /**
   * @return record type
   */
  public DNSRecordType getRecordType() {
    return (_recordType != null ? _recordType : DNSRecordType.TYPE_IGNORE);
  }

  /**
   * @return record class
   */
  public DNSRecordClass getRecordClass() {
    return (_dnsClass != null ? _dnsClass : DNSRecordClass.CLASS_UNKNOWN);
  }

  /**
   * @return true if unique
   */
  public boolean isUnique() {
    return _unique;
  }

  public Map<Fields, String> getQualifiedNameMap() {
    return Collections.unmodifiableMap(_qualifiedNameMap);
  }

  /**
   * Check if the record is expired.
   *
   * @param now update date
   * @return <code>true</code> is the record is expired, <code>false</code> otherwise.
   */
  public abstract boolean isExpired(long now);

  /**
   * @param dout
   * @exception IOException
   */
  protected void toByteArray(DataOutputStream dout) throws IOException {
    dout.write(this.getName().getBytes("UTF8"));
    dout.writeShort(this.getRecordType().indexValue());
    dout.writeShort(this.getRecordClass().indexValue());
  }

  /**
   * Creates a byte array representation of this record. This is needed for tie-break tests
   * according to draft-cheshire-dnsext-multicastdns-04.txt chapter 9.2.
   *
   * @return byte array representation
   */
  protected byte[] toByteArray() {
    try {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      DataOutputStream dout = new DataOutputStream(bout);
      this.toByteArray(dout);
      dout.close();
      return bout.toByteArray();
    } catch (IOException e) {
      throw new InternalError();
    }
  }

  /** Overriden, to return a value which is consistent with the value returned by equals(Object). */
  @Override
  public int hashCode() {
    return this.getKey().hashCode()
        + this.getRecordType().indexValue()
        + this.getRecordClass().indexValue();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(200);
    sb.append('[')
        .append(this.getClass().getSimpleName())
        .append('@')
        .append(System.identityHashCode(this));
    sb.append(" type: ").append(this.getRecordType());
    sb.append(", class: ").append(this.getRecordClass());
    sb.append((_unique ? "-unique," : ","));
    sb.append(" name: ").append(_name);
    sb.append(']');

    return sb.toString();
  }
}
