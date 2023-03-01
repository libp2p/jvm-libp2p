package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.simulate.stream.simpleLatencyDelayer
import io.libp2p.simulate.topology.RandomNPeers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class PeerBandwidth(
    val inbound: BandwidthDelayer,
    val outbound: BandwidthDelayer
) {
    companion object {
        val UNLIMITED = PeerBandwidth(BandwidthDelayer.UNLIM_BANDWIDTH, BandwidthDelayer.UNLIM_BANDWIDTH)
    }
}

data class MessageValidation(
    val validationDelay: Duration,
    val validationResult: ValidationResult
)

typealias BandwidthGenerator = (GossipSimPeer) -> PeerBandwidth
typealias LatencyGenerator = (StreamSimConnection) -> MessageDelayer
typealias MessageValidationGenerator = (GossipSimPeer, MessageApi) -> MessageValidation

data class GossipSimConfig(
    val totalPeers: Int = 10000,

    val topics: List<Topic>,

    val gossipProtocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,
    val gossipParams: GossipParams = GossipParams(),
    val gossipScoreParams: GossipScoreParams = GossipScoreParams(),
    val additionalHeartbeatDelay: RandomDistribution<Duration> = RandomDistribution.const(Duration.ZERO),
    val messageGenerator: GossipPubMessageGenerator = trickyPubSubMsgSizeEstimator(true),

    val bandwidthGenerator: BandwidthGenerator = { PeerBandwidth.UNLIMITED },
    val latencyGenerator: LatencyGenerator = { MessageDelayer.NO_DELAYER },
    val messageValidationGenerator: MessageValidationGenerator =
        constantValidationGenerator(0.milliseconds, ValidationResult.Valid),

    val topology: Topology = RandomNPeers(10),
    val peersTimeShift: RandomDistribution<Duration> = RandomDistribution.const(Duration.ZERO),

    val warmUpDelay: Duration = 5.seconds,
    val sentMessageCount: Int = 10,
    val startRandomSeed: Long = 0,
    val iterationThreadsCount: Int = 1,
    val parallelIterationsCount: Int = 1,
)

fun constantValidationGenerator(
    validationDelay: Duration,
    validationResult: ValidationResult = ValidationResult.Valid
): MessageValidationGenerator =
    { _, _ -> MessageValidation(validationDelay, validationResult) }

fun constantLatencyGenerator(latency: Duration): LatencyGenerator =
    { it.simpleLatencyDelayer(latency) }

fun constantBandwidthGenerator(bandwidth: Bandwidth): BandwidthGenerator = peerBandwidthGenerator { bandwidth }
fun peerBandwidthGenerator(bandwidthSupplier: (GossipSimPeer) -> Bandwidth): BandwidthGenerator = { gossipSimPeer ->
    val bandwidth = bandwidthSupplier(gossipSimPeer)
    PeerBandwidth(
        AccurateBandwidthTracker(
            bandwidth,
            gossipSimPeer.simExecutor,
            gossipSimPeer.currentTime,
            name = "[$gossipSimPeer]-in"
        ),
        AccurateBandwidthTracker(
            bandwidth,
            gossipSimPeer.simExecutor,
            gossipSimPeer.currentTime,
            name = "[$gossipSimPeer]-out"
        )
    )
}
