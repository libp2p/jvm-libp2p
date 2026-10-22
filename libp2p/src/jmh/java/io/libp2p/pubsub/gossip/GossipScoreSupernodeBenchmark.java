package io.libp2p.pubsub.gossip;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.Protocol;
import io.libp2p.pubsub.DefaultPubsubMessage;
import io.libp2p.pubsub.gossip.builders.GossipTopicScoreParamsBuilder;
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl;
import io.libp2p.tools.schedulers.TimeControllerImpl;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import pubsub.pb.Rpc;

/**
 * Models how the gossip router calls {@link DefaultGossipScore#score} on an Ethereum supernode:
 * every peer is meshed in every topic, topic params use the Eth2 values (12 s time-in-mesh quantum)
 * and the score reads the real wall clock, as it does in production.
 *
 * <ul>
 *   <li>{@link #repeatedScore}: the heartbeat pattern, many score() calls per peer with no state
 *       change in between
 *   <li>{@link #scoreAfterMessage}: the acceptRequestsFrom pattern, a message delivered on one
 *       topic and then the peer score requested
 *   <li>{@link #messageOnly}: the message delivery alone, to subtract from {@link
 *       #scoreAfterMessage}
 * </ul>
 *
 * Results are per peer (one score() call, or one delivery plus one score() call).
 *
 * <p>Setup computes every topic score of a peer within the same millisecond, so all per-topic
 * caches expire together and {@link #repeatedScore} runs almost entirely on the per-peer sum cache.
 * On a live node topic caches expire at staggered times, so the sum is rebuilt roughly every
 * timeInMeshQuantum / topicCount per peer; each rebuild costs about what {@link #scoreAfterMessage}
 * measures on top of {@link #messageOnly}.
 */
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GossipScoreSupernodeBenchmark {

  private static final int PEER_COUNT = 100;

  @Param({"64", "200"})
  private int topicCount;

  private DefaultGossipScore score;
  private List<PeerId> peerIds;
  private List<DefaultPubsubMessage> topicMessages;
  private int nextTopic = 0;

  @Setup
  public void setup() {
    List<String> topics =
        IntStream.range(0, topicCount)
            .mapToObj(i -> "/eth2/00000000/topic_" + i + "/ssz_snappy")
            .collect(Collectors.toList());

    GossipTopicScoreParams topicParams =
        new GossipTopicScoreParamsBuilder()
            .topicWeight(0.5)
            .timeInMeshWeight(0.03)
            .timeInMeshQuantum(Duration.ofSeconds(12))
            .timeInMeshCap(300.0)
            .firstMessageDeliveriesWeight(1.0)
            .firstMessageDeliveriesDecay(0.99)
            .firstMessageDeliveriesCap(23.0)
            .meshMessageDeliveriesWeight(-0.7)
            .meshMessageDeliveriesDecay(0.99)
            .meshMessageDeliveriesThreshold(14.0)
            .meshMessageDeliveriesCap(139.0)
            .meshMessageDeliveriesActivation(Duration.ofSeconds(384))
            .meshMessageDeliveryWindow(Duration.ofSeconds(2))
            .meshFailurePenaltyWeight(-0.7)
            .meshFailurePenaltyDecay(0.99)
            .invalidMessageDeliveriesWeight(-140.0)
            .invalidMessageDeliveriesDecay(0.99)
            .build();
    Map<String, GossipTopicScoreParams> topicParamMap =
        topics.stream().collect(Collectors.toMap(Function.identity(), __ -> topicParams));
    GossipScoreParams scoreParams =
        new GossipScoreParams(
            new GossipPeerScoreParams(),
            new GossipTopicsScoreParams(new GossipTopicScoreParams(), topicParamMap),
            0,
            0,
            0,
            0,
            0);

    // the decay task is scheduled on a controlled executor whose time never advances: decay is
    // not part of what is measured here
    ControlledExecutorServiceImpl executor = new ControlledExecutorServiceImpl();
    executor.setTimeController(new TimeControllerImpl());
    score = new DefaultGossipScore(scoreParams, executor, System::currentTimeMillis);

    peerIds = Stream.generate(PeerId::random).limit(PEER_COUNT).collect(Collectors.toList());
    topicMessages =
        topics.stream()
            .map(t -> new DefaultPubsubMessage(Rpc.Message.newBuilder().addTopicIDs(t).build()))
            .collect(Collectors.toList());

    for (int i = 0; i < PEER_COUNT; i++) {
      PeerId peerId = peerIds.get(i);
      score.notifyConnected(
          peerId,
          Multiaddr.empty()
              .withComponent(Protocol.IP4, new byte[] {10, 0, (byte) (i >>> 8), (byte) i})
              .withComponent(Protocol.TCP, new byte[] {0x23, 0x28}));
      for (int t = 0; t < topicCount; t++) {
        score.notifyMeshed(peerId, topics.get(t));
        score.notifyUnseenValidMessage(peerId, topicMessages.get(t));
      }
      score.score(peerId);
    }
  }

  private void deliverMessage(PeerId peerId) {
    score.notifyUnseenValidMessage(peerId, topicMessages.get(nextTopic));
    nextTopic = (nextTopic + 1) % topicCount;
  }

  @Benchmark
  @OperationsPerInvocation(PEER_COUNT)
  public void repeatedScore(Blackhole bh) {
    for (int i = 0; i < PEER_COUNT; i++) {
      bh.consume(score.score(peerIds.get(i)));
    }
  }

  @Benchmark
  @OperationsPerInvocation(PEER_COUNT)
  public void scoreAfterMessage(Blackhole bh) {
    for (int i = 0; i < PEER_COUNT; i++) {
      PeerId peerId = peerIds.get(i);
      deliverMessage(peerId);
      bh.consume(score.score(peerId));
    }
  }

  @Benchmark
  @OperationsPerInvocation(PEER_COUNT)
  public void messageOnly() {
    for (int i = 0; i < PEER_COUNT; i++) {
      deliverMessage(peerIds.get(i));
    }
  }
}
