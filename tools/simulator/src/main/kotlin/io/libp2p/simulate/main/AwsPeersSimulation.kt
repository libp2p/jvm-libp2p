package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.delay.latency.ClusteredNodesConfig
import io.libp2p.simulate.delay.latency.aws.AwsLatencies
import io.libp2p.simulate.delay.latency.aws.AwsRegion
import io.libp2p.simulate.delay.latency.aws.AwsRegion.*
import io.libp2p.simulate.stats.getStats
import io.libp2p.simulate.stats.toLongDescrString
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log

fun main() {
    AwsPeersSimulation().run()
}

class AwsPeersSimulation(
    val logger: (String) -> Unit = { log(it) },

    val clusteredNodesConfig: ClusteredNodesConfig<AwsRegion> = ClusteredNodesConfig(
        listOf(
            EU_NORTH_1,
            EU_CENTRAL_1,
            EU_WEST_1,
            EU_WEST_2,
            AP_NORTHEAST_1,
            AP_NORTHEAST_2,
            AP_SOUTHEAST_1,
            AP_SOUTHEAST_2,
            AP_SOUTH_1,
            SA_EAST_1,
            CA_CENTRAL_1,
            US_EAST_1,
            US_EAST_2,
            US_WEST_1,
            US_WEST_2,
        ).map { it to 50 },
        { c1, c2 ->
            AwsLatencies.SAMPLE.getLatency(c1, c2)
        },
        5
    ),
    val nodePeerCount: Int = 10,
    val testTopic: Topic = Topic("Topic-1"),
    val messageSize: Int = 32 * 1024,

    val sendingPeersCount: Int = 50,
    val messagesPerPeerCount: Int = 5,

    val simConfig: GossipSimConfig = GossipSimConfig(
        totalPeers = clusteredNodesConfig.totalNodeCount,
        topics = listOf(testTopic),
        gossipParams = GossipParams(),
        topology = RandomNPeers(nodePeerCount),
//        messageValidationGenerator = constantValidationGenerator(10.milliseconds),
//        bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(100)),
        latencyDelayGenerator = clusteredNodesConfig.latencyDistribution.toLatencyGenerator()
    )
) {

    data class RunResult(
        val delaysBySendingPeer: Map<Int, List<List<Long>>>
    )

    val simulation by lazy {
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
//        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(publisherPeer, messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
//        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
//        logger("No more pending messages at time ${simulation.network.timeController.time}")
    }

    fun gatherDelayResults(): List<Long> {
        val ret = simulation.gatherPubDeliveryStats().deliveryDelays
        simulation.clearAllMessages()
        return ret
    }

    fun runPublishingPeer(publisherPeer: Int = 0): List<List<Long>> {
        return List(messagesPerPeerCount) {
            publishMessage(publisherPeer)
            val delays = gatherDelayResults()
            logger("From [$publisherPeer], message #$it, deliver stats: " + delays.getStats().toLongDescrString())
            delays
        }
    }

    fun runAndCollectResults(): RunResult {
        return RunResult(
            List(sendingPeersCount) {
                it to runPublishingPeer(it)
            }.toMap()
        )
    }

    fun run() {
        val result = runAndCollectResults()
        // print result
    }
}