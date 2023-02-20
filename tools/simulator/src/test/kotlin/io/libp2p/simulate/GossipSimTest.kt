package io.libp2p.simulate

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.delay.LoggingDelayer.Companion.logging
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.asFixedTopology
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.minutes
import io.libp2p.simulate.util.seconds
import io.libp2p.simulate.util.transpose
import io.libp2p.tools.log
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*
import java.util.function.Consumer

class GossipSimTest {

    @Test
    fun simplest1() {
        val timeController = TimeControllerImpl()

        val createPeer = {
            val peer = GossipSimPeer(listOf(Topic("aaa")), "1", Random())
            peer.routerBuilder = SimGossipRouterBuilder().also {
                it.serializeMessagesToBytes = true
            }

            peer.pubsubLogs = { true }
            peer.simExecutor = ControlledExecutorServiceImpl(timeController)
            peer.currentTime = { timeController.time }
            peer
        }

        val p1 = createPeer()
        val p2 = createPeer()

        p1.connect(p2).get()
        var gotIt = false
        p2.api.subscribe(Consumer { gotIt = true }, Topic("a"))
        val p1Pub = p1.api.createPublisher(p1.keyPair.first, 0)
        p1Pub.publish("Hello".toByteArray().toByteBuf(), Topic("a"))

        Assertions.assertTrue(gotIt)
    }

    @Test
    fun regroupTest() {
        val t1 = listOf(
            mapOf(
                "a" to 11,
                "b" to 12,
                "c" to 13
            ),
            mapOf(
                "a" to 21,
                "b" to 22,
                "c" to 23
            )
        )

        val t2 = t1.transpose()

        Assertions.assertEquals(2, t2["a"]!!.size)
        Assertions.assertEquals(11, t2["a"]!![0])
        Assertions.assertEquals(21, t2["a"]!![1])

        val t3 = t2.transpose()
        Assertions.assertEquals(t1, t3)
    }

    @Test
    fun testMinimal() {
        testMinimalImpl(false)
//        testMinimalImpl(true)
    }

    fun testMinimalImpl(decoupled: Boolean) {
//        val decoupled = false

        val blockSize = 128 * 1024
        val blobCount = 4
        val blobSize = 128 * 1024

        val blockTopic = Topic(BlocksTopic)
        val blobTopic = Topic("/eth2/00000000/beacon_blob/ssz_snappy")
        val simConfig = GossipSimConfig(
            totalPeers = 4,
            topics = listOf(blockTopic, blobTopic),
//            topology = RandomNPeers(10),
            topology = TopologyGraph.customTopology(
                0 to 1,
                0 to 2,
                0 to 3,
//                    0 to 4,
//                    0 to 5,
//                    0 to 6,
//                    0 to 7,
//                    0 to 8,
//                    0 to 9,
//                    0 to 10,
            ).asFixedTopology(),
            gossipValidationDelay = 10.millis,
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
                heartbeatInterval = 1.minutes
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
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.flatMap { e ->
                e.value.map { it.receivedTime - e.key.sentTime }
            }
        }
        println("Delivery stats: $msgDelayStats")
    }
}
