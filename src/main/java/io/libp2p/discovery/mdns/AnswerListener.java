package io.libp2p.discovery.mdns;

import io.libp2p.discovery.mdns.impl.DNSRecord;

import java.util.EventListener;
import java.util.List;

public interface AnswerListener extends EventListener {
    void answersReceived(List<DNSRecord> answers);
}
