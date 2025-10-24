package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.millis
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipPeerScoreParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.LineaTopology
import io.libp2p.tools.log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class Gossiping(
    val d: Int = 8,
    val dLow: Int = 6,
    val dHigh: Int = d * 2,
    val dLazy: Int = 6,
    val fanoutTTL: Duration = 60.seconds,
    val gossipSize: Int = 3,
    val history: Int = 6,
    val heartbeatInterval: Duration = 700.milliseconds,
    val seenTTL: Duration = 700.milliseconds * 1115,
    val floodPublishMaxMessageSizeThreshold: Int = 1 shl 14, // 16KiB
    val gossipFactor: Double = 0.25,
    val considerPeersAsDirect: Boolean = false
) {
    override fun toString(): String {
        return "d=$d, " +
                "dLow=$dLow, " +
                "dHigh=$dHigh, " +
                "dLazy=$dLazy, " +
                "fanoutTTL=$fanoutTTL, " +
                "gossipSize=$gossipSize, " +
                "history=$history, " +
                "gossipFactor=$gossipFactor, " +
                "considerPeersAsDirect=$considerPeersAsDirect"
    }
}

fun main() {
    val sequencerCount: Int = 1
    val internalNodeCount: Int = 3
    val edgeNodeCount: Int = 7
    val partnerNodeCount: Int = 300
    val messages = 100
    log("Starting Linea simulation with " +
            "sequencers=$sequencerCount, " +
            "internalNodes=$internalNodeCount, " +
            "edgeNodes=$edgeNodeCount, " +
            "partnerNodes=$partnerNodeCount, " +
            "messages=$messages, " +
            "interNodeLatency=300ms"
    )
    val gossipConfigs = mapOf(
        "default" to Gossiping(),
        "considerPeersAsDirect" to Gossiping(considerPeersAsDirect = true),
        "gossipFactor=1" to Gossiping(gossipFactor = 1.0),
        "dHigh=310" to Gossiping(dHigh = 310),
        "d=310" to Gossiping(d= 310),
        "gossipSize-history" to Gossiping(gossipSize = 6, history = 12)
    )
    for((name, gossipConfig) in gossipConfigs)
    {
        LineaSimulation(
            name= name,
            sequencerCount = sequencerCount,
            internalNodeCount = internalNodeCount,
            edgeNodeCount = edgeNodeCount,
            partnerNodeCount = partnerNodeCount,
            gossipingConfig = gossipConfig
        ).publishMessage()
    }
}



class LineaSimulation(
    val name: String,
    val logger: (String) -> Unit = { log(it) },
    val testTopic: Topic = Topic("Topic-1"),
    val messageSize: Int = 1,
    val sequencerCount: Int = 1,
    val internalNodeCount: Int = 3,
    val edgeNodeCount: Int = 7,
    val partnerNodeCount: Int = 300,

    val simConfig: GossipSimConfig = GossipSimConfig(
        totalPeers = sequencerCount + internalNodeCount + edgeNodeCount + partnerNodeCount,
        topics = listOf(testTopic),
        topology = LineaTopology(
            sequencerCount = sequencerCount,
            internalNodeCount = internalNodeCount,
            edgeNodeCount = edgeNodeCount,
            partnerNodeCount = partnerNodeCount
        ),
        messageValidationGenerator = constantValidationGenerator(10.milliseconds),
        bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(1000)),
        latencyGenerator = constantLatencyGenerator(300.milliseconds),
    ),
    val gossipingConfig: Gossiping = Gossiping(),
    gossipParams: GossipParams = GossipParamsBuilder()
        .heartbeatInterval(gossipingConfig.heartbeatInterval.toJavaDuration())
        .gossipHistoryLength(gossipingConfig.history)
        .D(gossipingConfig.d)
        .DLow(gossipingConfig.dLow)
        .DLazy(gossipingConfig.dLazy)
        .DHigh(gossipingConfig.dHigh)
        .fanoutTTL(gossipingConfig.fanoutTTL.toJavaDuration())
        .seenTTL(gossipingConfig.seenTTL.toJavaDuration())
        .gossipSize(gossipingConfig.gossipSize)
        .floodPublishMaxMessageSizeThreshold(gossipingConfig.floodPublishMaxMessageSizeThreshold)
        .gossipFactor(gossipingConfig.gossipFactor)
        .build(),
    gossipScoreParams: GossipScoreParams = GossipScoreParams(
        peerScoreParams = GossipPeerScoreParams(isDirect = {_ -> gossipingConfig.considerPeersAsDirect})
    ),
    val gossipRouterBuilderFactory: GossipRouterBuilderFactory = {
        SimGossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }
) {

    val simulation = run {
        val simNetwork = GossipSimNetwork(simConfig, gossipRouterBuilderFactory)
        simNetwork.createAllPeers()
        simNetwork.connectAllPeers()
//        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork)
    }

    fun publishMessage(sequencer: Int = 0, messages: Int = 100) {
        simulation.publishMessage(sequencer, messageSize, testTopic)
        for(i in 1 until messages) {
            simulation.forwardTime(2.seconds)
            simulation.publishMessage(sequencer, messageSize, testTopic)
        }

        simulation.forwardTimeUntilAllPubDelivered(step = 100.milliseconds, maxDuration = 20.minutes)
        val messageResult = simulation.gossipMessageCollector.gatherResult()
//        val receivers = messageResult.publishMessages.groupBy { it.simMsgId }.mapValues { it.value.map { it.origMsg.receivingPeer.toString().toInt() }.toSet()}
//        val nodesNotReceived = receivers.mapValues { rcvdSet ->
//            (1 until simulation.network.peers.size).filter { peerId -> peerId !in rcvdSet.value }
//        }
//        logger("Nodes not received messages: $nodesNotReceived")
        val deliveryStats = simulation.gatherPubDeliveryStats()
        val deliveryAggrStats = StatsFactory.DEFAULT.createStats(deliveryStats.deliveryDelays)
//        logger("Delivery stats: $deliveryAggrStats")
        logger("name=$name, " +
                "allBroadcastsReceived=${simulation.isAllMessagesDelivered()}, " +
                "maxBroadcastLatency(ms)=${deliveryAggrStats.getDescriptiveStatistics().max}, " +
                "totalNetworkTraffic=${messageResult.getTotalTraffic()}, " +
                "totalNetworkMessages=${messageResult.messages.size}, " +
                "$gossipingConfig")
    }

}
