// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.libp2p.discovery.mdns.impl.constants.DNSRecordClass;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType;

import io.libp2p.discovery.mdns.ServiceInfo;
import io.libp2p.discovery.mdns.impl.constants.DNSConstants;

/**
 * A DNS question.
 *
 * @author Arthur van Hoff, Pierre Frisch
 */
public class DNSQuestion extends DNSEntry {
    private static Logger logger = Logger.getLogger(DNSQuestion.class.getName());

    /**
     * Pointer question.
     */
    private static class Pointer extends DNSQuestion {
        Pointer(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique) {
            super(name, type, recordClass, unique);
        }

        @Override
        public void addAnswers(JmDNSImpl jmDNSImpl, Set<DNSRecord> answers) {
            // find matching services
            for (ServiceInfo serviceInfo : jmDNSImpl.getServices().values()) {
                this.addAnswersForServiceInfo(jmDNSImpl, answers, (ServiceInfoImpl) serviceInfo);
            }
        }
    }

    DNSQuestion(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique) {
        super(name, type, recordClass, unique);
    }

    /**
     * Create a question.
     *
     * @param name
     *            DNS name to be resolved
     * @param type
     *            Record type to resolve
     * @param recordClass
     *            Record class to resolve
     * @param unique
     *            Request unicast response (Currently not supported in this implementation)
     * @return new question
     */
    public static DNSQuestion newQuestion(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique) {
        return (type == DNSRecordType.TYPE_PTR)
                ? new Pointer(name, type, recordClass, unique)
                : null;
    }

    /**
     * Adds answers to the list for our question.
     *
     * @param jmDNSImpl
     *            DNS holding the records
     * @param answers
     *            List of previous answer to append.
     */
    public void addAnswers(JmDNSImpl jmDNSImpl, Set<DNSRecord> answers) {
        // By default we do nothing
    }

    protected void addAnswersForServiceInfo(JmDNSImpl jmDNSImpl, Set<DNSRecord> answers, ServiceInfoImpl info) {
        if (info != null) {
            if (this.getName().equalsIgnoreCase(info.getQualifiedName()) || this.getName().equalsIgnoreCase(info.getType()) || this.getName().equalsIgnoreCase(info.getTypeWithSubtype())) {
                answers.addAll(info.answers(this.getRecordClass(), DNSRecordClass.UNIQUE, DNSConstants.DNS_TTL, jmDNSImpl.getLocalHost()));
            }
            logger.log(Level.FINE, "{} DNSQuestion({}).addAnswersForServiceInfo(): info: {}\n{}", List.of(jmDNSImpl.getName(), this.getName(), info, answers));
        }
    }

    @Override
    public boolean isExpired(long now) {
        return false;
    }
}