// Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl.tasks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.libp2p.discovery.mdns.impl.DNSIncoming;
import io.libp2p.discovery.mdns.impl.DNSOutgoing;
import io.libp2p.discovery.mdns.impl.DNSQuestion;
import io.libp2p.discovery.mdns.impl.DNSRecord;
import io.libp2p.discovery.mdns.impl.JmDNSImpl;
import io.libp2p.discovery.mdns.impl.constants.DNSConstants;

/**
 * The Responder sends a single answer for the specified service infos and for the host name.
 */
public class Responder extends DNSTask {
    static Logger logger = LoggerFactory.getLogger(Responder.class.getName());

    private final DNSIncoming _in;

    private final InetAddress _addr;
    private final int         _port;

    private final boolean     _unicast;

    public Responder(JmDNSImpl jmDNSImpl, DNSIncoming in, InetAddress addr, int port) {
        super(jmDNSImpl);
        this._in = in;
        this._addr = addr;
        this._port = port;
        this._unicast = (port != DNSConstants.MDNS_PORT);
    }

    @Override
    protected String getName() {
        return "Responder(" + (this.dns() != null ? this.dns().getName() : "") + ")";
    }

    @Override
    public String toString() {
        return super.toString() + " incoming: " + _in;
    }

    @Override
    public void start() {
        int delay =
                DNSConstants.RESPONSE_MIN_WAIT_INTERVAL +
                JmDNSImpl.getRandom().nextInt(
                        DNSConstants.RESPONSE_MAX_WAIT_INTERVAL -
                              DNSConstants.RESPONSE_MIN_WAIT_INTERVAL + 1
                ) -
                _in.elapseSinceArrival();
        if (delay < 0) {
            delay = 0;
        }
        logger.trace("{}.start() Responder chosen delay={}", this.getName(), delay);

        _scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        // We use these sets to prevent duplicate records
        Set<DNSQuestion> questions = new HashSet<DNSQuestion>();
        Set<DNSRecord> answers = new HashSet<DNSRecord>();

        try {
            // Answer questions
            for (DNSQuestion question : _in.getQuestions()) {
                logger.debug("{}.run() JmDNS responding to: {}", this.getName(), question);

                // for unicast responses the question must be included
                if (_unicast) {
                    questions.add(question);
                }

                question.addAnswers(this.dns(), answers);
            }

            // respond if we have answers
            if (!answers.isEmpty()) {
                logger.debug("{}.run() JmDNS responding", this.getName());

                DNSOutgoing out = new DNSOutgoing(DNSConstants.FLAGS_QR_RESPONSE | DNSConstants.FLAGS_AA, !_unicast, _in.getSenderUDPPayload());
                if (_unicast) {
                    out.setDestination(new InetSocketAddress(_addr, _port));
                }
                out.setId(_in.getId());
                for (DNSQuestion question : questions) {
                    out = this.addQuestion(out, question);
                }
                for (DNSRecord answer : answers) {
                    out = this.addAnswer(out, answer);
                }
                if (!out.isEmpty())
                    this.dns().send(out);
            }
        } catch (Throwable e) {
            logger.warn(this.getName() + "run() exception ", e);
        }
        _scheduler.shutdown();
    }

    private DNSOutgoing addAnswer(DNSOutgoing out, DNSRecord rec) throws IOException {
        DNSOutgoing newOut = out;
        try {
            newOut.addAnswer(rec);
        } catch (final IOException e) {
            int flags = newOut.getFlags();
            boolean multicast = newOut.isMulticast();
            int maxUDPPayload = newOut.getMaxUDPPayload();
            int id = newOut.getId();

            newOut.setFlags(flags | DNSConstants.FLAGS_TC);
            newOut.setId(id);
            this.dns().send(newOut);

            newOut = new DNSOutgoing(flags, multicast, maxUDPPayload);
            newOut.addAnswer(rec);
        }
        return newOut;
    }
}