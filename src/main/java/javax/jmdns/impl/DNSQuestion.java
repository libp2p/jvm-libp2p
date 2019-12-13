// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package javax.jmdns.impl;

import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.jmdns.ServiceInfo;
import javax.jmdns.impl.constants.DNSConstants;
import javax.jmdns.impl.constants.DNSRecordClass;
import javax.jmdns.impl.constants.DNSRecordType;

/**
 * A DNS question.
 *
 * @author Arthur van Hoff, Pierre Frisch
 */
public class DNSQuestion extends DNSEntry {
    private static Logger logger = LogManager.getLogger(DNSQuestion.class.getName());

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
            logger.debug("{} DNSQuestion({}).addAnswersForServiceInfo(): info: {}\n{}", jmDNSImpl.getName(), this.getName(), info, answers);
        }
    }

    /*
     * (non-Javadoc)
     * @see javax.jmdns.impl.DNSEntry#isStale(long)
     */
    @Override
    public boolean isStale(long now) {
        return false;
    }

    /*
     * (non-Javadoc)
     * @see javax.jmdns.impl.DNSEntry#isExpired(long)
     */
    @Override
    public boolean isExpired(long now) {
        return false;
    }

    /**
     * Checks if we are the only to be able to answer that question.
     *
     * @param jmDNSImpl
     *            DNS holding the records
     * @return <code>true</code> if we are the only one with the answer to the question, <code>false</code> otherwise.
     */
    public boolean iAmTheOnlyOne(JmDNSImpl jmDNSImpl) {
        return false;
    }

    /*
     * (non-Javadoc)
     * @see javax.jmdns.impl.DNSEntry#toString(java.lang.StringBuilder)
     */
    @Override
    public void toString(final StringBuilder sb) {
        // do nothing
    }

}