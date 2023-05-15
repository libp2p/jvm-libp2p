package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.TopologyGraph
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.delay.LoggingDelayer.Companion.logging
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.simulate.topology.asFixedTopology
import io.libp2p.tools.log
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class GossipSimTest {

    @Test
    fun test1() {
        val simConfig = GossipSimConfig(
            totalPeers = 3,
            topics = listOf(Topic(BlocksTopic)),
            topology = AllToAllTopology(),
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            SimGossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
//                it.serialize = true
            }
        }

        val simPeerModifier = { _: Int, _: GossipSimPeer ->
//            peer.pubsubLogs = { true }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        val publishTime = simulation.forwardTime(1.seconds)

        simulation.publishMessage(0)
        simulation.forwardTime(1.seconds)

        val res = simulation.gatherPubDeliveryStats()

        assertThat(res.deliveries).hasSize(2)

        assertThat(res.deliveries[0].toPeer.simPeerId).isEqualTo(1)
        assertThat(res.deliveries[0].receivedTime).isEqualTo(publishTime)

        assertThat(res.deliveries[1].toPeer.simPeerId).isEqualTo(2)
        assertThat(res.deliveries[1].receivedTime).isEqualTo(publishTime)

        println("Done")
    }

    @Test
    fun testMinimal() {
        testMinimalImpl(false)
        testMinimalImpl(true)
    }

    fun testMinimalImpl(decoupled: Boolean) {
        val blockSize = 128 * 1024
        val blobCount = 4
        val blobSize = 128 * 1024

        val blockTopic = Topic(BlocksTopic)
        val blobTopic = Topic("/eth2/00000000/beacon_blob/ssz_snappy")
        val simConfig = GossipSimConfig(
            totalPeers = 4,
            topics = listOf(blockTopic, blobTopic),
            topology = TopologyGraph.customTopology(
                0 to 1,
                0 to 2,
                0 to 3,
            ).asFixedTopology(),
            messageValidationGenerator = constantValidationGenerator(10.milliseconds),
            bandwidthGenerator = { peer ->
                PeerBandwidth(
                    AccurateBandwidthTracker(Bandwidth(1_000_000), peer.simExecutor, peer.currentTime),
//                        .logging { log("${peer.currentTime()}: [${peer.name}] <==   $it") }
                    AccurateBandwidthTracker(
                        Bandwidth(1_000_000),
                        peer.simExecutor,
                        peer.currentTime,
                        peer.name
                    )
                        .logging { log("${peer.currentTime()}: [${peer.name}]   ==> $it") },
                )
            },
            startRandomSeed = 2
        )

        val gossipParams = Eth2DefaultGossipParams
            .copy(
                D = 3,
                DLow = 1,
                DHigh = 3,
                DOut = 0,
                heartbeatInterval = 1.minutes.toJavaDuration()
            )
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            SimGossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)

        log("Forwarding heartbeat time...")
        simulation.forwardTime(65.seconds)

        simulation.clearAllMessages()

        println("Sending message ")
        if (decoupled) {
            simulation.publishMessage(0, blockSize, blockTopic)
            simulation.publishMessage(0, blobSize * blobCount, blobTopic)
        } else {
            simulation.publishMessage(0, blockSize + blobSize * blobCount, blockTopic)
        }
        simulation.forwardTime(1.minutes)

        println("Gathering results...")
        val results = simulation.gatherPubDeliveryStats()

        val msgDelayStats = StatsFactory.DEFAULT.createStats(results.deliveryDelays)
        println("Delivery stats: $msgDelayStats")

        if (decoupled) {
            assertThat(msgDelayStats.getCount()).isEqualTo(6)
        } else {
            assertThat(msgDelayStats.getCount()).isEqualTo(3)
        }
        assertThat(msgDelayStats.getDescriptiveStatistics().max).isCloseTo(2000.0, Percentage.withPercentage(10.0))
        assertThat(msgDelayStats.getDescriptiveStatistics().min).isGreaterThan(200.0)
    }
}
