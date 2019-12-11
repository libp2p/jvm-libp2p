package javax.jmdns;

import javax.jmdns.impl.DNSRecord;
import java.util.EventListener;
import java.util.List;

public interface AnswerListener extends EventListener {
    void answersReceived(List<DNSRecord> answers);
}
