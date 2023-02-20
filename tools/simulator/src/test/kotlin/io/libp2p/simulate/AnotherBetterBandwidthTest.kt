package io.libp2p.simulate

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.delay.AccurateBandwidthTracker.Message
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.seconds
import org.assertj.core.api.Assertions
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class AnotherBetterBandwidthTest {

    @Test
    fun testCalcDeliverTimes1() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_000)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(202_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes2() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_500)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_500, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes3() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(50, 200_500)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_050, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_600, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes4() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1, 200_500)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_002, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_502, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes5() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(1, 200_000),
            Message(1, 200_000)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes6() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000)
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[4]).isCloseTo(200_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes7() {
        val bandwidth = Bandwidth(1_000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_000),
            Message(1000, 201_900),
            Message(10, 202_900),
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(202_050, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_050, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(203_010, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(202_920, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes8() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(924956, 70_000),
            Message(924956, 70_000),
            Message(924956, 70_000),
            Message(1130435, 72_774),
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(73_905, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes9() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(900_000, 10_000),
            Message(900_000, 10_000),
            Message(1_100_000, 11_800),
        )
        val t = AccurateBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(11_800, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(11_800, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(12_900, Offset.offset(2))
    }

    val topic = Topic("aaa")
    val simConfig = GossipSimConfig(
        totalPeers = 2,
        topics = listOf(topic),
        topology = AllToAllTopology(),
        gossipValidationDelay = 0.millis
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

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier).also {
        println("Creating peers...")
        it.createAllPeers()
    }
    val peer0 = simNetwork.peers[0]!!
    val peer1 = simNetwork.peers[1]!!

    @Test
    fun `test smaller inbound bandwidth preserves sequential message delivery`() {
        peer0.outboundBandwidth =
            AccurateBandwidthTracker(Bandwidth(150000), peer0.simExecutor, peer0.currentTime, "[0]-out")
        peer1.inboundBandwidth =
            AccurateBandwidthTracker(Bandwidth(100000), peer1.simExecutor, peer1.currentTime, "[1]-in")

        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(5.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.publishMessage(0, 100000, topic)
        simulation.forwardTime(1.seconds)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val messageResults = simulation.gatherMessageResults()
            val resList = messageResults.entries.toList()
            Assertions.assertThat(resList).hasSize(3)
            run {
                val (origMsg, res) = resList[0]
                Assertions.assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(2000, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[1]
                Assertions.assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(3000, Offset.offset(100))
            }
        }
    }
}
