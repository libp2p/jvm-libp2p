package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    SimpleSimulation().publishMessage()
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
        gossipParams = GossipParams(),
        topology = RandomNPeers(nodePeerCount),
        messageValidationGenerator = constantValidationGenerator(10.milliseconds),
        bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(100)),
        latencyGenerator = constantLatencyGenerator(50.milliseconds),
    )
) {

    val simulation = run {
        val simNetwork = GossipSimNetwork(simConfig)
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork)
    }

    fun publishMessage(publisherPeer: Int = 0) {
        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(publisherPeer, messageSize, testTopic)

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
