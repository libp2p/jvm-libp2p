package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.millis
import io.libp2p.tools.log

fun main() {
    SimpleSimulation().run()
}

class SimpleSimulation(
    val logger: (String) -> Unit = { log(it) },
    nodeCount: Int = 1000,
    nodePeerCount: Int = 30,
    val testTopic: Topic = Topic("Topic-1"),
    val messageSize: Int = 32 * 1024,

    val simConfig: GossipSimConfig = GossipSimConfig(
        totalPeers = nodeCount,
        topics = listOf(testTopic),
        topology = RandomNPeers(nodePeerCount),
        gossipValidationDelay = 10.millis,
        bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(100)),
        latencyGenerator = constantLatencyGenerator(50.millis),
    ),

    gossipParams: GossipParams = GossipParamsBuilder().build(),
    gossipScoreParams: GossipScoreParams = GossipScoreParams(),

    val gossipRouterBuilderFactory: GossipRouterBuilderFactory = {
        SimGossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }
) {

    val simulation = run {
        val simNetwork = GossipSimNetwork(simConfig, gossipRouterBuilderFactory)
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork)
    }

    fun run() {
        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(0, messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
        logger("No more pending messages at time ${simulation.network.timeController.time}")

        val messagesResult = simulation.gossipMessageCollector.gatherResult()
        logger("Network statistics: messages: ${messagesResult.getTotalMessageCount()}, traffic: ${messagesResult.getTotalTraffic()}")

        val deliveryStats = simulation.gatherPubDeliveryStats()
        val deliveryAggrStats = StatsFactory.DEFAULT.createStats(deliveryStats.deliveryDelays)
        logger("Delivery stats: $deliveryAggrStats")
    }
}
