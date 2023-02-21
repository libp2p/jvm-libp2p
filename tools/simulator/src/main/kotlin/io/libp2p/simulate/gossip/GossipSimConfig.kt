package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.simulate.stream.simpleLatencyDelayer
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.seconds
import java.time.Duration
import kotlin.time.toKotlinDuration

data class PeerBandwidth(
    val inbound: BandwidthDelayer,
    val outbound: BandwidthDelayer
) {
    companion object {
        val UNLIMITED = PeerBandwidth(BandwidthDelayer.UNLIM_BANDWIDTH, BandwidthDelayer.UNLIM_BANDWIDTH)
    }
}

typealias BandwidthGenerator = (GossipSimPeer) -> PeerBandwidth
typealias LatencyGenerator = (StreamSimConnection) -> MessageDelayer

data class GossipSimConfig(
    val totalPeers: Int = 10000,
    val badPeers: Int = 0,

    val topics: List<Topic>,

    val messageGenerator: GossipPubMessageGenerator = trickyPubSubMsgSizeEstimator(true),

    val bandwidthGenerator: BandwidthGenerator = { PeerBandwidth.UNLIMITED },
    val latencyGenerator: LatencyGenerator = { MessageDelayer.NO_DELAYER },
    val gossipValidationDelay: Duration = 0.millis,

    val topology: Topology = RandomNPeers(10),
    val peersTimeShift: RandomDistribution = RandomDistribution.const(0.0),

    val warmUpDelay: Duration = 5.seconds,
    val generatedNetworksCount: Int = 1,
    val sentMessageCount: Int = 10,
    val startRandomSeed: Long = 0,
    val iterationThreadsCount: Int = 1,
    val parallelIterationsCount: Int = 1,
)

fun constantLatencyGenerator(latency: Duration): LatencyGenerator =
    { it.simpleLatencyDelayer(latency.toKotlinDuration()) }

fun constantBandwidthGenerator(bandwidth: Bandwidth): BandwidthGenerator = { gossipSimPeer ->
    PeerBandwidth(
        AccurateBandwidthTracker(
            bandwidth,
            gossipSimPeer.simExecutor,
            gossipSimPeer.currentTime
        ),
        AccurateBandwidthTracker(
            bandwidth,
            gossipSimPeer.simExecutor,
            gossipSimPeer.currentTime
        )
    )
}
