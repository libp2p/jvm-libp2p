package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.*
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.seconds
import java.time.Duration

data class PeerBandwidth(
    val inbound: BandwidthDelayer,
    val outbound: BandwidthDelayer
) {
    companion object {
        val UNLIMITED = PeerBandwidth(BandwidthDelayer.UNLIM_BANDWIDTH, BandwidthDelayer.UNLIM_BANDWIDTH)
    }
}

data class GossipSimConfig(
    val totalPeers: Int = 10000,
    val badPeers: Int = 0,

    val topics: List<Topic>,

    val messageGenerator: GossipPubMessageGenerator = trickyPubSubMsgSizeEstimator(true),

    val bandwidthGenerator: (GossipSimPeer) -> PeerBandwidth = { PeerBandwidth.UNLIMITED },
    val latencyGenerator: (StreamSimConnection) -> MessageDelayer = { MessageDelayer.NO_DELAYER },
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
