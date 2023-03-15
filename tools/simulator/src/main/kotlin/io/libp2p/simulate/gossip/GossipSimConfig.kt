package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.TimeDelayer
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.simulate.topology.RandomNPeers
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

data class InOutBandwidth(
    val inbound: Bandwidth,
    val outbound: Bandwidth = inbound
)

data class MessageValidation(
    val validationDelay: Duration,
    val validationResult: ValidationResult
)

typealias LatencyDelayGenerator = (StreamSimConnection) -> MessageDelayer
typealias MessageValidationGenerator = (MessageApi) -> MessageValidation

data class GossipSimPeerConfig(
    // Gossip router config
    val gossipProtocol: PubsubProtocol,
    val gossipParams: GossipParams,
    val gossipScoreParams: GossipScoreParams,
    val additionalHeartbeatDelay: Duration,

    // Gossip simulation config
    val topics: List<Topic>,
    val messageValidationGenerator: MessageValidationGenerator,

    // Other
    val bandwidth: InOutBandwidth,
)

data class GossipSimConfig(
    val peerConfigs: List<GossipSimPeerConfig>,

    val messageGenerator: GossipPubMessageGenerator = trickyPubSubMsgSizeEstimator(true),
    val latency: LatencyDistribution = LatencyDistribution.createConst(ZERO),

    val topology: Topology = RandomNPeers(10),
    val warmUpDelay: Duration = 10.seconds,
    val randomSeed: Long = 0,
) {

    val totalPeers: Int get() = peerConfigs.size
}

data class GossipSimPeerConfigGenerator(
    // Gossip router config
    val gossipProtocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,
    val gossipParams: GossipParams = GossipParams(),
    val gossipScoreParams: GossipScoreParams = GossipScoreParams(),
    val additionalHeartbeatDelay: RandomDistribution<Duration> =
        RandomDistribution.uniform(0, gossipParams.heartbeatInterval.toMillis()).milliseconds(),

    // Gossip simulation config
    val topics: List<Topic>,
    val messageValidationDelays: RandomDistribution<Duration> = RandomDistribution.const(ZERO),

    // Network config
    val bandwidths: RandomDistribution<Bandwidth> = RandomDistribution.const(Bandwidth.UNLIM),
) {

    fun generate(randomSeed: Long): Sequence<GossipSimPeerConfig> = sequence {
        val random = Random(randomSeed)
        val additionalHeartbeatDelayValue = additionalHeartbeatDelay.newValue(random)
        val messageValidationDelaysValue = messageValidationDelays.newValue(random)
        val bandwidthsValue = bandwidths.newValue(random)
        while (true) {
            val msgValidationDelay = messageValidationDelaysValue.next()
            yield(
                GossipSimPeerConfig(
                    gossipProtocol,
                    gossipParams,
                    gossipScoreParams,
                    additionalHeartbeatDelayValue.next(),
                    topics,
                    { MessageValidation(msgValidationDelay, ValidationResult.Valid) },
                    InOutBandwidth(bandwidthsValue.next())
                )
            )
        }
    }

    fun generate(randomSeed: Long, count: Int): List<GossipSimPeerConfig> = generate(randomSeed).take(count).toList()
}

