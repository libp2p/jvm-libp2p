package io.libp2p.simulate.main.scenario

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

enum class Decoupling {
    Coupled,
    OnlyBlockDecoupled,
    DecoupledManyTopics,
    DecoupledSingleTopic
}

class BlobDecouplingScenario(
    val logger: (String) -> Unit = { log(it) },

    val messageValidationDelay: Duration = 10.milliseconds,
    val latency: LatencyDistribution =
        LatencyDistribution.createUniformConst(1.milliseconds, 50.milliseconds),

    val nodeCount: Int = 1000,
    val nodePeerCount: Int = 30,
    val messageCount: Int = 5,

    val blockSize: Int = 128 * 1024,
    val blobCount: Int = 4,
    val blobSize: Int = 128 * 1024,
    val randomSeed: Long = 3L,

    val gossipProtocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,
    val gossipParams: GossipParams = Eth2DefaultGossipParams,
    val gossipScoreParams: GossipScoreParams = Eth2DefaultScoreParams,

    val sendingPeerBand: Bandwidth = 100.mbitsPerSecond,
    val sendingPeerFilter: (GossipSimPeer) -> Boolean = {
        it.outboundBandwidth.totalBandwidth == sendingPeerBand
    },

    val peerBands: RandomDistribution<Bandwidth> = RandomDistribution.const(100.mbitsPerSecond),
    val peerMessageValidationDelays: RandomDistribution<Duration> = RandomDistribution.const(messageValidationDelay),

    val routerBuilderFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
    val simConfigModifier: (GossipSimConfig) -> GossipSimConfig = { it }
) {
    val blockTopic = Topic(BlocksTopic)
    val blobTopics = (0 until blobCount)
        .map {
            Topic("/eth2/00000000/beacon_blob_$it/ssz_snappy")
        }
    val simConfig = GossipSimConfig(
        peerConfigs = GossipSimPeerConfigGenerator(
            topics = listOf(blockTopic) + blobTopics,
            gossipProtocol = gossipProtocol,
            gossipParams = gossipParams,
            gossipScoreParams = gossipScoreParams,
            messageValidationDelays = peerMessageValidationDelays,
            bandwidths = peerBands,
        ).generate(randomSeed, nodeCount),
        topology = RandomNPeers(nodePeerCount),
        latency = latency,
        randomSeed = randomSeed
    ).let {
        simConfigModifier(it)
    }

    val simNetwork = GossipSimNetwork(simConfig, routerBuilderFactory).also { simNetwork ->
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())
    }

    val sendingPeerIndexes = simNetwork.peers
        .filterValues { sendingPeerFilter(it) }
        .keys
        .toList()

    val simulation = run {
        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork).also { simulation ->
            logger("Forwarding heartbeat time...")
            simulation.forwardTime(gossipParams.heartbeatInterval.toKotlinDuration())
            logger("Cleaning warmup messages and network stats...")
            simulation.clearAllMessages()
        }
    }

    fun test(decoupleType: Decoupling, methodMessageCount: Int = messageCount) {
        for (i in 0 until methodMessageCount) {
            testSingle(decoupleType, i)
        }
    }

    fun testSingle(decoupleType: Decoupling, sendingPeerIndex: Int) {
        val sendingPeer = sendingPeerIndexes[sendingPeerIndex]
        logger("Sending message from peer $sendingPeer")

        when(decoupleType) {
            Decoupling.Coupled -> {
                simulation.publishMessage(sendingPeer, blockSize + blobSize * blobCount, blockTopic)
            }
            Decoupling.OnlyBlockDecoupled -> {
                simulation.publishMessage(sendingPeer, blockSize, blockTopic)
                simulation.publishMessage(sendingPeer, blobSize * blobCount, blobTopics[0])
            }
            Decoupling.DecoupledSingleTopic -> {
                simulation.publishMessage(sendingPeer, blockSize, blockTopic)
                (0 until blobCount).forEach {
                    simulation.publishMessage(sendingPeer, blobSize, blockTopic)
                }
            }
            Decoupling.DecoupledManyTopics -> {
                simulation.publishMessage(sendingPeer, blockSize, blockTopic)
                (0 until blobCount).forEach {
                    simulation.publishMessage(sendingPeer, blobSize, blobTopics[it])
                }
            }
        }

        val t1 = simulation.network.timeController.time
        simulation.forwardTimeUntilAllPubDelivered()
        val t2 = simulation.network.timeController.time - t1
        logger("All messages delivered in $t2")
        simulation.forwardTimeUntilNoPendingMessages()
    }
}