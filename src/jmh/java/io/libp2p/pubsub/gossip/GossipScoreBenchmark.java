package io.libp2p.pubsub.gossip;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.Protocol;
import io.libp2p.pubsub.DefaultPubsubMessage;
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl;
import io.libp2p.tools.schedulers.TimeController;
import io.libp2p.tools.schedulers.TimeControllerImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import pubsub.pb.Rpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@State(Scope.Thread)
@Fork(5)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class GossipScoreBenchmark {

    private final int peerCount = 5000;
    private final int connectedCount = 2000;
    private final int topicCount = 128;

    private final List<String> topics = IntStream
            .range(0, topicCount)
            .mapToObj(i -> "Topic-" + i)
            .collect(Collectors.toList());

    private final List<PeerId> peerIds = Stream.generate(PeerId::random).limit(peerCount).collect(Collectors.toList());
    private final List<Multiaddr> peerAddresses = IntStream
            .range(0, peerCount)
            .mapToObj(idx ->
                    Multiaddr.empty()
                            .withComponent(Protocol.IP4, new byte[]{(byte) (idx >>> 8 & 0xFF), (byte) (idx & 0xFF), 0, 0})
                            .withComponent(Protocol.TCP, new byte[]{0x23, 0x28}))
            .collect(Collectors.toList());

    private final TimeController timeController = new TimeControllerImpl();
    private final ControlledExecutorServiceImpl controlledExecutor = new ControlledExecutorServiceImpl();
    private final GossipScoreParams gossipScoreParams;
    private final DefaultGossipScore score;

    public GossipScoreBenchmark() {
        Map<String, GossipTopicScoreParams> topicParamMap = topics.stream()
                .collect(Collectors.toMap(Function.identity(), __ -> new GossipTopicScoreParams()));
        GossipTopicsScoreParams gossipTopicsScoreParams = new GossipTopicsScoreParams(new GossipTopicScoreParams(), topicParamMap);

        gossipScoreParams = new GossipScoreParams(new GossipPeerScoreParams(), gossipTopicsScoreParams, 0, 0, 0, 0, 0);
        controlledExecutor.setTimeController(timeController);
        score = new DefaultGossipScore(gossipScoreParams, controlledExecutor, timeController::getTime);

        for (int i = 0; i < peerCount; i++) {
            PeerId peerId = peerIds.get(i);
            score.notifyConnected(peerId, peerAddresses.get(i));
            for (String topic : topics) {
                notifyUnseenMessage(peerId, topic);
            }
        }

        for (int i = connectedCount; i < peerCount; i++) {
            score.notifyDisconnected(peerIds.get(i));
        }
    }

    private void notifyUnseenMessage(PeerId peerId, String topic) {
        Rpc.Message message = Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .build();
        score.notifyUnseenValidMessage(peerId, new DefaultPubsubMessage(message));
    }

    @Benchmark
    public void scoresDelay0(Blackhole bh) {
        for (int i = 0; i < connectedCount; i++) {
            double s = score.score(peerIds.get(i));
            bh.consume(s);
        }
    }

    @Benchmark
    public void scoresDelay100(Blackhole bh) {
        timeController.addTime(100);

        for (int i = 0; i < connectedCount; i++) {
            double s = score.score(peerIds.get(i));
            bh.consume(s);
        }
    }

    @Benchmark
    public void scoresDelay10000(Blackhole bh) {
        timeController.addTime(10000);

        for (int i = 0; i < connectedCount; i++) {
            double s = score.score(peerIds.get(i));
            bh.consume(s);
        }
    }

    /**
     * Uncomment for debugging
     */
//    public static void main(String[] args) {
//        GossipScoreBenchmark benchmark = new GossipScoreBenchmark();
//        Blackhole blackhole = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
//        benchmark.scoresDelay0(blackhole);
//    }
}
