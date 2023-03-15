package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
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
            peerConfigs = GossipSimPeerConfigGenerator(
                topics = listOf(Topic(BlocksTopic)),
            ).generate(0, 3),
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
    fun `test latency and validation delays are fixed per peer and connection and vary with seed`() {
        fun run(seed: Long): GossipMessageResult {
            val simConfig = GossipSimConfig(
                peerConfigs = GossipSimPeerConfigGenerator(
                    topics = listOf(Topic(BlocksTopic)),
                    messageValidationDelays = RandomDistribution.uniform(100, 110).milliseconds(),
                    bandwidths = RandomDistribution.const(Bandwidth.UNLIM)
                ).generate(seed, 4),
                latency = LatencyDistribution.createUniformConst(200.milliseconds, 210.milliseconds),
                topology = TopologyGraph.customTopology(
                    0 to 1,
                    1 to 2,
                    2 to 3,
                ).asFixedTopology(),
                randomSeed = seed
            )

            val simNetwork = GossipSimNetwork(simConfig)
            println("Creating peers...")
            simNetwork.createAllPeers()
            println("Connecting peers...")
            simNetwork.connectAllPeers()

            println("Creating simulation...")
            val simulation = GossipSimulation(simConfig, simNetwork)

            repeat(10) {
                simulation.publishMessage(0)
                simulation.forwardTime(5.seconds)
            }

            return simulation.gossipMessageCollector.gatherResult()
        }

        fun gatherActualConnectionLatencies(res: GossipMessageResult): Map<Pair<SimPeer, SimPeer>, List<Long>> {
            return res.messages.groupBy {
                listOf(it.sendingPeer, it.receivingPeer)
                    .sortedBy { it.simPeerId }
                    .let { it[0] to it[1] }
            }.mapValues { (_, msg) ->
                msg.map { it.delay }
            }
        }

        fun gatherPeerValidationDelays(res: GossipMessageResult, peerId: SimPeerId): List<Long> {
            val deliveryResult = res.getGossipPubDeliveryResult()
            return deliveryResult.originalMessages.map { origPublish ->
                val p1 = deliveryResult.deliveries
                    .filter { it.initialPublishMsg == origPublish && it.toPeer.simPeerId == peerId }
                val p2 = deliveryResult.deliveries
                    .filter { it.initialPublishMsg == origPublish && it.fromPeer.simPeerId == peerId }
                assertThat(p1).hasSize(1)
                assertThat(p2).hasSize(1)

                p2[0].origGossipMsg.origMsg.sendTime - p1[0].receivedTime
            }
        }

        fun gatherValidationDelays(res: GossipMessageResult): Map<SimPeerId, List<Long>> =
            listOf(1, 2)
                .associateWith {
                    gatherPeerValidationDelays(res, it)
                }


        val res1 = run(1)

        val latencies1 = gatherActualConnectionLatencies(res1)
            .also { latencies ->
                assertThat(latencies).hasSize(3)
                val connLatency = latencies
                    .map { (_, lat) ->
                        assertThat(lat.distinct()).hasSize(1)
                        lat[0]
                    }
                    .onEach {
                        assertThat(it).isBetween(200, 210)
                    }
                assertThat(connLatency.distinct()).hasSize(3)

            }

        val valDelays1 = gatherValidationDelays(res1)
            .also { delays ->
                val peersDelay = delays.values
                    .map {
                        assertThat(it).hasSize(10)
                        assertThat(it.distinct()).hasSize(1)
                        it[0]
                    }
                    .onEach {
                        assertThat(it).isBetween(100, 110)
                    }
                assertThat(peersDelay.distinct()).hasSize(2)
            }

        run(1).also {
            // check the same results with the same seed
            assertThat(gatherActualConnectionLatencies(it)).isEqualTo(latencies1)
            assertThat(gatherValidationDelays(it)).isEqualTo(valDelays1)
        }

        run(2).also {
            // check different results with a different seed
            assertThat(gatherActualConnectionLatencies(it)).isNotEqualTo(latencies1)
            assertThat(gatherValidationDelays(it)).isNotEqualTo(valDelays1)
        }

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
            peerConfigs = GossipSimPeerConfigGenerator(
                topics = listOf(blockTopic, blobTopic),
                messageValidationDelays = RandomDistribution.const(10.milliseconds),
                bandwidths = RandomDistribution.const(Bandwidth(1_000_000))
            ).generate(0, 4),
            topology = TopologyGraph.customTopology(
                0 to 1,
                0 to 2,
                0 to 3,
            ).asFixedTopology(),
            randomSeed = 2
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
