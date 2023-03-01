package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.bandwidthDistribution
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.gossip.GossipSimulation
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.stats.GroupByRangeAggregator
import io.libp2p.simulate.stats.Stats
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.util.byIndexes
import io.libp2p.simulate.util.countValues
import io.libp2p.simulate.util.infiniteIterator
import io.libp2p.simulate.util.infiniteLoopIterator
import io.libp2p.tools.log

fun main() {
    BlobDecouplingSimulation().run()
}


class BlobDecouplingSimulation() {

    fun printResults(simulation: GossipSimulation) {
        log("Gathering results...")

        val messageDelayStats = gatherMessageDelayStats(simulation)
        log("Results:")
        log("Delivery stats: $messageDelayStats")

        val messagesResult = simulation.gossipMessageCollector.gatherResult()
        log(
            "Network stats: msgCount: ${messagesResult.getTotalMessageCount()}, " +
                "msgsSize: ${messagesResult.getTotalTraffic()}"
        )
    }

    fun gatherMessageDelayStats(simulation: GossipSimulation): Stats {
        val allMessageDelays = simulation
            .gossipMessageCollector
            .gatherResult()
            .getGossipPubDeliveryResult()
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

    fun printGossipDetailedResults(simulation: GossipSimulation) {
        val simNetwork = simulation.network
        val gossipMessages = simulation.gossipMessageCollector.gatherResult()

        println("IWANT messages count: " + gossipMessages.iWantMessages.size)

        val slowestMessage = gossipMessages.receivedPublishMessagesByPeerFastest
            .values
            .flatten()
            .maxByOrNull { it.origMsg.receiveTime }!!
        println("The longest message: $slowestMessage")

        val longestPath =
            gossipMessages.findPubMessagePath(slowestMessage.origMsg.receivingPeer, slowestMessage.simMsgId)
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
            .map { it to simulation.cfg.messageGenerator.sizeEstimator(it.message) }
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

    fun run() {
        val bandwidths = bandwidthDistributions.byIndexes(2)
        val slowBandwidth = Bandwidth.mbitsPerSec(10)

        bandwidths.forEach { band ->
            fun getResults(sim: BlobDecouplingScenario): String {
                val messageDelayStats = gatherMessageDelayStats(sim.simulation).getStatisticalSummary()
                val messagesResult = sim.simulation.gossipMessageCollector.gatherResult()
                return "${messageDelayStats.min.toLong()}\t" +
                        "${messageDelayStats.mean.toLong()}\t" +
                        "${messageDelayStats.max.toLong()}\t" +
                        "${messagesResult.messages.size}\t" +
                        "${messagesResult.getTotalTraffic()}"
            }

            fun getRangedDelays(sim: BlobDecouplingScenario): GroupByRangeAggregator {
                val groupedDelays = sim.simulation.gatherPubDeliveryStats()
                    .aggregateSlowestByPublishTime()
                    .groupBy {
                        if (it.toPeer.inboundBandwidth.totalBandwidth == slowBandwidth)
                            "Slow" else "Fast"
                    }
                    .mapValues { it.value.deliveryDelays }
                return GroupByRangeAggregator(groupedDelays)
            }

            fun createSimulation() =
                BlobDecouplingScenario(
//                logger = {},
                    nodeCount = 1000,
                    peerBands = band,
                    gossipParams = Eth2DefaultGossipParams.copy(
                        floodPublish = false
                    )
//                randomSeed = 2
                )

            val coupledDelays = createSimulation().let {
                it.testCoupled()
                println("$band\tCoupled\t${getResults(it)}\n")
                getRangedDelays(it)
            }

            val decoupledDelays = createSimulation().let {
                it.testAllDecoupled()
                println("$band\tDecoupled\t${getResults(it)}\n")
                getRangedDelays(it)
            }

            (coupledDelays.withMappedNames { "Coupled $it" } + decoupledDelays.withMappedNames { "Decoupled $it" })
                .aggregate(20)
                .formatToString()
                .also { println(it) }
        }
    }

    companion object {
        val bandwidthDistributions = listOf(
            bandwidthDistribution(
                100.mbitsPerSecond to 100
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 10,
                100.mbitsPerSecond to 80,
                190.mbitsPerSecond to 10,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 20,
                100.mbitsPerSecond to 60,
                190.mbitsPerSecond to 20,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 33,
                100.mbitsPerSecond to 33,
                190.mbitsPerSecond to 33,
            ),
        )
    }
}
