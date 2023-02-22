package io.libp2p.simulate.delay

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.seconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class BandwidthTest {

    val topic = Topic("aaa")
    val simConfig = GossipSimConfig(
        totalPeers = 2,
        topics = listOf(topic),
        topology = AllToAllTopology(),
        gossipValidationDelay = 0.millis,
    )

    val gossipParams = Eth2DefaultGossipParams
    val gossipScoreParams = Eth2DefaultScoreParams
    val gossipRouterCtor = { _: Int ->
        SimGossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }

    val simPeerModifier = { _: Int, _: GossipSimPeer ->
//            peer.pubsubLogs = { true }
    }

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier).also {
        println("Creating peers...")
        it.createAllPeers()
    }
    val peer0 = simNetwork.peers[0]!!
    val peer1 = simNetwork.peers[1]!!

    @Test
    fun testInboundLarger() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(200000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(2100, Offset.offset(100))
            println(deliveryResult)
    }

    @Test
    fun testInboundLargerWithLatency() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(200000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        simNetwork.network.activeConnections.forEach {
            it.connectionLatency = TimeDelayer(peer0.simExecutor) { 1000.milliseconds }
        }

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(3100, Offset.offset(100))
    }

    @Test
    fun testInboundSmaller() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(50000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(4100, Offset.offset(100))
    }

    @Test
    fun testInboundSmallerWithLatency() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(50000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        simNetwork.network.activeConnections.forEach {
            it.connectionLatency = TimeDelayer(peer0.simExecutor) { 1000.milliseconds }
        }

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(5100, Offset.offset(100))
    }

    @Test
    fun testSequentialBandwidth() {
        peer0.outboundBandwidth =
            SequentialBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = BandwidthDelayer.UNLIM_BANDWIDTH
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(5.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.publishMessage(0, 100000, topic)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries).hasSize(3)
                assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(2100, Offset.offset(100))
                assertThat(deliveryResult.deliveries[1].deliveryDelay).isCloseTo(3100, Offset.offset(100))
                assertThat(deliveryResult.deliveries[2].deliveryDelay).isCloseTo(5100, Offset.offset(100))
        }
        simulation.clearAllMessages()

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 100000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val deliveryResult = simulation.gatherPubDeliveryStats()
            assertThat(deliveryResult.deliveries).hasSize(3)
            assertThat(deliveryResult.deliveries[0].deliveryDelay).isCloseTo(2100, Offset.offset(100))
            assertThat(deliveryResult.deliveries[1].deliveryDelay).isCloseTo(2600, Offset.offset(100))
            assertThat(deliveryResult.deliveries[2].deliveryDelay).isCloseTo(4100, Offset.offset(100))
        }
    }
}
