package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.AccurateBandwidthTracker
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.Stats
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stream.randomLatencyDelayer
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.chunked
import io.libp2p.simulate.util.countByRanges
import io.libp2p.simulate.util.countValues
import io.libp2p.simulate.util.millis
import io.libp2p.simulate.util.minutes
import io.libp2p.simulate.util.toMap
import io.libp2p.tools.log
import java.time.Duration
import java.util.*

data class PeerBandwidthValue(
    val inbound: Bandwidth,
    val outbound: Bandwidth
)

class BlobDecouplingSimulation(
    val logger: (String) -> Unit = { log(it) },

    val messageValidationDelay: Duration = 10.millis,
    val latency: RandomDistribution = RandomDistribution.uniform(0.0, 50.0),

    val nodeCount: Int = 1000,
    val nodePeerCount: Int = 30,
    val messageCount: Int = 5,

    val blockSize: Int = 128 * 1024,
    val blobCount: Int = 4,
    val blobSize: Int = 128 * 1024,
    val randomSeed: Long = 3L,
    val rnd: Random = Random(randomSeed),

    val floodPublish: Boolean = true,

    val sendingPeerBand: Bandwidth = Bandwidth.mbitsPerSec(100),

    val peerBands: Iterator<Bandwidth> = iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
        }
    }

) {

    val peerBandwidths: (GossipSimPeer) -> PeerBandwidthValue = { _ ->
        val inOutBand = peerBands.next()
        PeerBandwidthValue(inOutBand, inOutBand)
    }
    val bandwidthFactory: (PeerBandwidthValue, GossipSimPeer) -> PeerBandwidth = { band, peer ->
        PeerBandwidth(
            AccurateBandwidthTracker(band.inbound, peer.simExecutor, peer.currentTime, name = "[$peer]-in"),
            AccurateBandwidthTracker(band.outbound, peer.simExecutor, peer.currentTime, name = "[$peer]-out")
        )
    }

    val blockTopic = Topic(BlocksTopic)
    val blobTopics = (0 until blobCount)
        .map {
            Topic("/eth2/00000000/beacon_blob_$it/ssz_snappy")
        }
    val simConfig = GossipSimConfig(
        totalPeers = nodeCount,
        topics = listOf(blockTopic) + blobTopics,
        topology = RandomNPeers(nodePeerCount),
        gossipValidationDelay = messageValidationDelay,
        bandwidthGenerator = {
            val band = peerBandwidths(it)
            bandwidthFactory(band, it)
        },
        latencyGenerator = { it.randomLatencyDelayer(latency.newValue(rnd)) },
        startRandomSeed = randomSeed
    )

    val gossipParams = Eth2DefaultGossipParams
        .copy(
//            heartbeatInterval = 1.minutes
            floodPublish = floodPublish
        )
    val gossipScoreParams = Eth2DefaultScoreParams
    val gossipRouterCtor = { _: Int ->
        SimGossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor).also { simNetwork ->
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())
    }

    val peerIndexesByBandwidth = simNetwork.peers.entries
        .groupBy { it.value.outboundBandwidth.totalBandwidth }
        .mapValues { it.value.map { it.key } }
    val sendingPeerIndexes = peerIndexesByBandwidth[sendingPeerBand]!!

    val simulation = run {
        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork).also { simulation ->
            logger("Forwarding heartbeat time...")
            simulation.forwardTime(gossipParams.heartbeatInterval)
            logger("Cleaning warmup messages and network stats...")
            simulation.clearAllMessages()
        }
    }

    fun testCoupled() {
        for (i in 0 until messageCount) {
            val sendingPeer = sendingPeerIndexes[i]
            logger("Sending message $i from peer $sendingPeer")
            simulation.publishMessage(sendingPeer, blockSize + blobSize * blobCount, blockTopic)

            val t1 = simulation.network.timeController.time
            simulation.forwardTimeUntilAllPubDelivered()
            val t2 = simulation.network.timeController.time - t1
            println("All messages delivered in $t2")
            simulation.forwardTimeUntilNoPendingMessages()
        }

        printResults()
    }

    fun testOnlyBlockDecoupled() {

        for (i in 0 until messageCount) {
            val sendingPeer = sendingPeerIndexes[i]
            logger("Sending message $i from peer $sendingPeer")
            simulation.publishMessage(sendingPeer, blockSize, blockTopic)
            simulation.publishMessage(sendingPeer, blobSize * blobCount, blobTopics[0])

            val t1 = simulation.network.timeController.time
            simulation.forwardTimeUntilAllPubDelivered()
            val t2 = simulation.network.timeController.time - t1
            println("All messages delivered in $t2")
            simulation.forwardTimeUntilNoPendingMessages()
        }

        printResults()
    }

    fun testAllDecoupled() {

        for (i in 0 until messageCount) {
            val sendingPeer = sendingPeerIndexes[i]
            logger("Sending message $i from peer $sendingPeer")
            simulation.publishMessage(sendingPeer, blockSize, blockTopic)
            (0 until blobCount).forEach {
                simulation.publishMessage(sendingPeer, blobSize, blobTopics[it])
            }

            val t1 = simulation.currentTimeSupplier()
            simulation.forwardTimeUntilAllPubDelivered(maxDuration = 3.minutes)
            val t2 = simulation.currentTimeSupplier()
            println(
                "All messages delivered in ${t2 - t1}, " +
                    "Pending message count: ${simulation.gossipMessageCollector.pendingMessages.size}, " +
                    getGossipStats(
                        simulation.gossipMessageCollector.gatherResult().slice(t1, t2)
                    )
            )
            simulation.forwardTimeUntilNoPendingMessages()
//            println("Traffic by bandwidth: " + gatherAvrgTrafficByBandwidth(t1, simulation.currentTimeSupplier()))
        }

        printResults()
    }

    fun printResults() {
        logger("Gathering results...")

        val messageDelayStats = gatherMessageDelayStats()
        logger("Results:")
        logger("Delivery stats: $messageDelayStats")

        val messagesResult = simulation.gossipMessageCollector.gatherResult()
        logger(
            "Network stats: msgCount: ${messagesResult.getTotalMessageCount()}, " +
                "msgsSize: ${messagesResult.getTotalTraffic()}"
        )
    }

    fun gatherMessageDelayStats(): Stats {
        val allMessageDelays = simulation
            .gatherPubDeliveryStats()
            .aggregateSlowestByPublishTime()
            .deliveryDelays
        return StatsFactory.DEFAULT.createStats(allMessageDelays)
    }

    fun getGossipStats(results: GossipMessageResult): String {
        val graftMsgCount = results.graftMessages.size
        val pruneMsgCount = results.pruneMessages.size
        val iHaveMsgCount = results.iHaveMessages.size
        val iWantMsgCount = results.iWantMessages.size
        val pubMsgCount = results.publishMessages.size
        val rawMsgCount = results.messages.size
        return "GossipMessagesStats: Total raw: $rawMsgCount, " +
            "Total parts: ${pubMsgCount + graftMsgCount + pruneMsgCount + iHaveMsgCount + iWantMsgCount}, " +
            "PUBLISH: $pubMsgCount, GRAFT: $graftMsgCount, PRUNE: $pruneMsgCount, IHAVE: $iHaveMsgCount, IWANT: $iWantMsgCount"
    }

    fun printGossipDetailedResults() {
        val gossipMessages = simulation.gossipMessageCollector.gatherResult()

        println("IWANT messages count: " + gossipMessages.iWantMessages.size)

        val slowestMessage = gossipMessages.receivedPublishMessagesByPeerFastest
            .values
            .flatten()
            .maxByOrNull { it.origMsg.receiveTime }!!
        println("The longest message: $slowestMessage")

        val longestPath =
            gossipMessages.findPubMessagePath(slowestMessage.origMsg.receivingPeer, slowestMessage.msgId)
        println("Longest path (${longestPath.size} hops): \n  " + longestPath.joinToString("\n  "))

        val fastestMessage = gossipMessages.receivedPublishMessagesByPeerFastest
            .values
            .flatten()
            .minByOrNull { it.origMsg.receiveTime }!!
        println("The fastest message: $fastestMessage")
        val peer0PubOutbounds = gossipMessages.publishMessages
            .filter { it.origMsg.sendingPeer == simNetwork.peers[0] }
        println("Peer 0 outbounds: \n" + peer0PubOutbounds.joinToString("\n").prependIndent("  "))

        val peer0AllOutbounds = gossipMessages.messages
            .filter { it.sendingPeer == simNetwork.peers[0] }
            .map { it to simConfig.messageGenerator.sizeEstimator(it.message) }
        println("Peer 0 all outbounds: \n" + peer0AllOutbounds.joinToString("\n").prependIndent("  "))

        simNetwork.peers.values.flatMap {
            it.router.mesh.map { (topic, peers) -> topic to peers.size }
        }
            .groupBy({ it.first }, { it.second })
            .also {
                println("Mesh sizes: ")
                it.forEach { (topic, meshSizes) ->
                    println("  [$topic]: " + meshSizes.countValues().toSortedMap())
                }
            }

        println("Peer0 meshes: " + simNetwork.peers[0]!!.router.mesh.mapValues { it.value.size })
    }
}

fun main() {
    val bandwidths = bandwidthDistributions.entries.toList()
        .let {
            listOf(/*it[0],*/ it[2])
        }.toMap()
    val slowBandwidth = Bandwidth.mbitsPerSec(10)

    bandwidths.forEach { (name, band) ->
        fun getResults(sim: BlobDecouplingSimulation): String {
            val messageDelayStats = sim.gatherMessageDelayStats().getStatisticalSummary()
            val messagesResult = sim.simulation.gossipMessageCollector.gatherResult()
            return "${messageDelayStats.min.toLong()}\t" +
                "${messageDelayStats.mean.toLong()}\t" +
                "${messageDelayStats.max.toLong()}\t" +
                "${messagesResult.messages.size}\t" +
                "${messagesResult.getTotalTraffic()}"
        }

        fun getRangedDelays(sim: BlobDecouplingSimulation): String {
            val delayRanges = (0L until 20_000L).chunked(20)

            val slowDelays = sim.simulation.gatherPubDeliveryStats()
                .aggregateSlowestByPublishTime()
                .filter {
                    sim.simulation.network.peers[it.deliveredMsg.receivedPeer]!!.inboundBandwidth.totalBandwidth == slowBandwidth
                }
                .deliveryDelays
            val slowCountByRanges = slowDelays.countByRanges(delayRanges)
            val fastDelays = sim.simulation.gatherPubDeliveryStats()
                .aggregateSlowestByPublishTime()
                .filter {
                    sim.simulation.network.peers[it.deliveredMsg.receivedPeer]!!.inboundBandwidth.totalBandwidth != slowBandwidth
                }
                .deliveryDelays
            val fastCountByRanges = fastDelays.countByRanges(delayRanges)

            return delayRanges
                .zip(slowCountByRanges)
                .zip(fastCountByRanges)
                .map { (rangeAndSlow, fast) ->
                    "${rangeAndSlow.first.first}\t${rangeAndSlow.second}\t$fast"
                }
                .joinToString("\n")
        }

        fun createSimulation() =
            BlobDecouplingSimulation(
//                logger = {},
                nodeCount = 1000,
                peerBands = band,
                floodPublish = false,
//                randomSeed = 2
            )

        createSimulation().also {
            it.testCoupled()
            println("$name\tCoupled\t${getResults(it)}\n" + getRangedDelays(it))
        }

        createSimulation().also {
            it.testAllDecoupled()
            println("$name\tDecoupled\t${getResults(it)}\n" + getRangedDelays(it))
        }
    }
}

val bandwidthDistributions = mapOf(
    "100% 100Mbps" to iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "10% 10Mbps, 80% 100Mbps, 10% 190Mbps" to iterator {
        yield(Bandwidth.mbitsPerSec(100))
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(190))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "20% 10Mbps, 60% 100Mbps, 20% 190Mbps" to iterator {
        yield(Bandwidth.mbitsPerSec(100))
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(190))
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "33% 10Mbps, 33% 100Mbps, 33% 190Mbps" to iterator {
        yield(Bandwidth.mbitsPerSec(100))
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(190))
        }
    }
)
