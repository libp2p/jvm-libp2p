package io.libp2p.simulate.main

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.choke.ChokeStrategyPerTopic
import io.libp2p.pubsub.gossip.choke.SimpleTopicChokeStrategy
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.ClusteredNodesConfig
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.delay.latency.aws.AwsLatencies
import io.libp2p.simulate.delay.latency.aws.AwsRegion
import io.libp2p.simulate.delay.latency.named
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.gossip.GossipSimulation
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.main.PeerHonesty.Honest
import io.libp2p.simulate.main.PeerHonesty.Malicious
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.main.scenario.Decoupling
import io.libp2p.simulate.main.scenario.MaliciousPeerManager
import io.libp2p.simulate.main.scenario.ResultPrinter
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.util.*
import io.libp2p.tools.log
import java.util.Random
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    EpisubSimulation().runAndPrint()
}

enum class PeerHonesty { Honest, Malicious }

class EpisubSimulation(
    val nodeCount: Int = 1000,
    val blockSize: Int = 128 * 1024,
    val blobSize: Int = 128 * 1024,
    val randomSeed: Long = 0L,

    val sendingPeerBandwidth: Bandwidth = 100.mbitsPerSecond,
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> =
        listOf(RandomDistribution.const(sendingPeerBandwidth)),
//        bandwidthDistributions,
    val maliciousPeersParams: List<RandomDistribution<PeerHonesty>> =
        listOf(
            RandomDistribution.discreteEven(Honest to 100, Malicious to 0),
//            RandomDistribution.discreteEven(Honest to 75, Malicious to 25),
//            RandomDistribution.discreteEven(Honest to 50, Malicious to 50),
//            RandomDistribution.discreteEven(Honest to 25, Malicious to 75),
//            RandomDistribution.discreteEven(Honest to 10, Malicious to 90),
//            RandomDistribution.discreteEven(Honest to 5, Malicious to 95),
        ),
    val decouplingParams: List<Decoupling> = listOf(
        Decoupling.Coupled,
        Decoupling.DecoupledManyTopics,
//        Decoupling.DecoupledSingleTopic,
    ),
    val meshParams: List<MeshSimParams> = listOf(
//        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 2),
//        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 4),
        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 6),
//        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 8),
//        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 12),
        MeshSimParams(PubsubProtocol.Gossip_V_1_2, 8),
    ),

    val latencyParams: List<LatencyDistribution> =
        listOf(
            LatencyDistribution.createConst(10.milliseconds),
//            LatencyDistribution.createConst(50.milliseconds),
//            LatencyDistribution.createConst(100.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 20.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 50.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 100.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 200.milliseconds),
//            awsLatencyDistribution
        ),
//        listOf(awsLatencyDistribution),

    val validationDelayParams: List<RandomDistribution<Duration>> =
//        listOf(RandomDistribution.const(10.milliseconds)),
        listOf(
            RandomDistribution.const(20.milliseconds),
//            RandomDistribution.discreteEven(
//                70.milliseconds to 33,
//                50.milliseconds to 33,
//                20.milliseconds to 33
//            ),
//            RandomDistribution.discreteEven(
//                300.milliseconds to 33,
//                100.milliseconds to 33,
//                10.milliseconds to 33
//            ),
//            RandomDistribution.uniform(10, 40).milliseconds(),
//            RandomDistribution.uniform(10, 100).milliseconds(),
//            RandomDistribution.uniform(10, 300).milliseconds(),
//            RandomDistribution.uniform(10, 600).milliseconds(),
        ),

    val paramsSet: List<SimParams> =
        cartesianProduct(
            decouplingParams,
            bandwidthsParams,
            validationDelayParams,
            latencyParams,
            meshParams,
            maliciousPeersParams
        ) {
            SimParams(it.second, it.third, it.fourth, it.first, it.fifth.gossipVersion, it.fifth.D, it.sixth)
        },
    val chokeWarmupMessageCount: Int = 10,
    val testMessageCount: Int = 10
) {

    data class MeshSimParams(
        val gossipVersion: PubsubProtocol,
        val D: Int
    )

    data class SimParams(
        val bandwidths: RandomDistribution<Bandwidth>,
        val validationDelays: RandomDistribution<Duration>,
        val latency: LatencyDistribution,
        val decoupling: Decoupling,
        val gossipVersion: PubsubProtocol,
        val D: Int,
        val honestPeers: RandomDistribution<PeerHonesty>
    )

    data class PeerChokeResult(
        val peer: GossipSimPeer,
        val meshSize: Int,
        val chokedCount: Int,
        val chokedByCount: Int
    )

    data class TopicChokeResult(
        val peerResults: List<PeerChokeResult>
    )

    data class ChokeResult(
        val topicResults: Map<Topic, TopicChokeResult>
    )

    data class NetworkResult(
        val messageCount: Int,
        val traffic: Long
    )

    data class RunResult(
        val deliveryDelays: List<Long>,
        val byIWantDeliveryRate: Double,
        val chokeResult: ChokeResult,
        val networkResult: NetworkResult
    )

    data class EpisubScenario(
        val blobDecouplingScenario: BlobDecouplingScenario,
        val maliciousPeerManager: MaliciousPeerManager
    )

    fun createBlobScenario(simParams: SimParams): EpisubScenario {
        var maliciousPeerManager: MaliciousPeerManager? = null // TODO fix dirty stuff
        val blobDecouplingScenario = BlobDecouplingScenario(
//                logger = {},
            blockSize = blockSize,
            blobSize = blobSize,
            sendingPeerFilter = {
                it.outboundBandwidth.totalBandwidth == sendingPeerBandwidth &&
                        it.simPeerId !in maliciousPeerManager!!.maliciousPeerIds
            },
            messageCount = 1,
            nodeCount = nodeCount,
            peerBands = simParams.bandwidths,
            latency = simParams.latency,
            gossipParams = Eth2DefaultGossipParams.copy(
                floodPublish = false,
                D = simParams.D,
                DLow = max(1, simParams.D - 2),
                DHigh = simParams.D + 2,
                DOut = 0
            ),
            peerMessageValidationDelays = simParams.validationDelays,
            gossipProtocol = simParams.gossipVersion,
            routerBuilderFactory = {
                SimGossipRouterBuilder().also {
                    it.chokeStrategy = ChokeStrategyPerTopic { SimpleTopicChokeStrategy(it) }
                }
            },
            simConfigModifier = {
                val maliciousSelector = simParams.honestPeers.newValue(Random(randomSeed))
                maliciousPeerManager = MaliciousPeerManager({ maliciousSelector.next() == Malicious }, it)
                maliciousPeerManager!!.maliciousConfig
            }
        )
        return EpisubScenario(blobDecouplingScenario, maliciousPeerManager!!)
    }

    fun runAndPrint() {
        val results = run(paramsSet)
        printResults(paramsSet.zip(results).toMap())
    }

    fun run(paramsSet: List<SimParams>): List<RunResult> =
        paramsSet.mapIndexed { idx, params ->
            log("Running ${idx + 1} of ${paramsSet.size}: $params")
            run(params)
        }

    fun run(params: SimParams): RunResult {
        val (scenario, maliciousPeerManager) = createBlobScenario(params)


        println("Worming up choking...")
        repeat(chokeWarmupMessageCount) {
            scenario.testSingle(params.decoupling, it)
        }

        val chokeResults = calcChokeResults(scenario.simulation)
//        val tmp1 =
//            if (params.gossipVersion.version == PubsubProtocol.Gossip_V_1_2.version) {
//                scenario.peerMessageValidationDelays
//                    .zip(chokeResults.topicResults.values.first().peerResults)
//            } else {
//                emptyList()
//            }

        scenario.simulation.clearAllMessages()

        maliciousPeerManager.propagateMessages = false
        println("Sending test messages...")
        repeat(testMessageCount) { sendingPeerIndex ->

//            val sendingPeer = scenario.simulation.network.peers[scenario.sendingPeerIndexes[sendingPeerIndex]]!!
//            val sendingRouter = sendingPeer.router
//            val topics = sendingRouter.mesh
//                .filterValues { it.isNotEmpty() }
//                .keys
//            val topicMeshes = topics
//                .associateWith {
//                    sendingRouter.mesh[it]!!.size to sendingRouter.chokedByPeers.getBySecond(it).size
//                }
//            println("Sending peer mesh: $topicMeshes")

//            val startT = scenario.simulation.currentTimeSupplier()
            scenario.testSingle(params.decoupling, sendingPeerIndex)

//            val runMessages = scenario.simulation.gossipMessageCollector
//                .gatherResult()
//                .slice(startT)
//            val allDeliveryResult = runMessages.getGossipPubDeliveryResult()
//            val deliveryResult = allDeliveryResult.aggregateSlowestByPublishTime()
//            val iWantDeliveries = deliveryResult.deliveries
//                .filter { runMessages.isByIWantPubMessage(it.origGossipMsg) }
//            println(
//                "Message $sendingPeerIndex, deliveryStats: " + deliveryResult.deliveryDelays.getStats() +
//                        ", IWant deliveries: ${iWantDeliveries.size}"
//            )
//            if (sendingPeer.simPeerId == 18) {
//                val sendingPeerMessages = runMessages.getPeerGossipMessages(sendingPeer)
//                println(sendingPeerMessages.joinToString("\n").prependIndent("  "))
//            }
        }

        return calcResult(scenario.simulation, chokeResults)
    }

    fun calcChokeResults(simulation: GossipSimulation): ChokeResult {
        val allTopics = simulation.network.peers.values.flatMap { it.router.mesh.keys }.distinct()

        val res = allTopics
            .associateWith { topic ->
                simulation.network.peers.values
                    .map {
                        PeerChokeResult(
                            it,
                            it.router.mesh[topic]?.size ?: 0,
                            it.router.chokedPeers.getBySecond(topic).size,
                            it.router.chokedByPeers.getBySecond(topic).size
                        )
                    }
                    .let { TopicChokeResult(it) }
            }
            .filterValues { it.peerResults.sumOf { it.chokedCount } > 0 }

        return ChokeResult(res)
    }

    fun calcResult(simulation: GossipSimulation, chokeResults: ChokeResult): RunResult {
        val messageResult = simulation.gossipMessageCollector.gatherResult()
        val messageGroups = simulation.publishedMessages
            .groupBy { it.sentTime }
            .values
            .map {
                it.map { it.simMessageId }.toSet()
            }

        val allDeliveryResult = messageResult.getGossipPubDeliveryResult()
        val deliveryResult = allDeliveryResult.aggregateSlowestByPublishTime()
        val iWantDeliveries = deliveryResult.deliveries
            .count { messageResult.isByIWantPubMessage(it.origGossipMsg) }

        return RunResult(
            allDeliveryResult
                .aggregateSlowestBySimMessageId(messageGroups)
                .deliveryDelays,
            iWantDeliveries.toDouble() / deliveryResult.deliveries.size,
            chokeResults,
            NetworkResult(messageResult.getTotalMessageCount(), messageResult.getTotalTraffic())
        )
    }

    fun printResults(runs: Map<SimParams, RunResult>) {
        val printer = ResultPrinter(runs).apply {
            addNumberStats { it.deliveryDelays }
                .apply {
                    addGeneric("count") { it.size }
                    addLong("min") { it.min }
                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addMetric("msgCount") { it.networkResult.messageCount }
            addMetric("traffic") { it.networkResult.traffic }
            addMetric("deliveryRatio") {
                it.deliveryDelays.size.toDouble() / ((nodeCount - 1) * testMessageCount)
            }
            addMetricDouble("byIWant", 3) { it.byIWantDeliveryRate }
        }

        println("Pretty results:")
        println("======================")
        println(printer.printPretty())
        println("\n\nTab separated results:")
        println("======================")
        println(printer.printTabSeparated())
    }

    companion object {
        val awsLatencyDistribution =
            ClusteredNodesConfig(
                RandomDistribution.discreteEven(
                    AwsRegion.EU_NORTH_1 to 50,
                    AwsRegion.EU_CENTRAL_1 to 50,
                    AwsRegion.EU_WEST_1 to 50,
                    AwsRegion.EU_WEST_2 to 50,
                    AwsRegion.AP_NORTHEAST_1 to 50,
                    AwsRegion.AP_NORTHEAST_2 to 50,
                    AwsRegion.AP_SOUTHEAST_1 to 50,
                    AwsRegion.AP_SOUTHEAST_2 to 50,
                    AwsRegion.AP_SOUTH_1 to 50,
                    AwsRegion.SA_EAST_1 to 50,
                    AwsRegion.CA_CENTRAL_1 to 50,
                    AwsRegion.US_EAST_1 to 50,
                    AwsRegion.US_EAST_2 to 50,
                    AwsRegion.US_WEST_1 to 50,
                    AwsRegion.US_WEST_2 to 50,
                ).newValue(Random(0)),
                { c1, c2 ->
                    AwsLatencies.SAMPLE.getLatency(c1, c2)
                },
                5
            )
                .latencyDistribution
                .named("AWS-1")

        val validatioDelayDistributions = listOf(
            RandomDistribution.discreteEven(
                70.milliseconds to 33,
                50.milliseconds to 33,
                20.milliseconds to 33
            )
        )

        val bandwidthDistributions = listOf(
            bandwidthDistribution(
                100.mbitsPerSecond to 100
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 10,
                100.mbitsPerSecond to 80,
                1000.mbitsPerSecond to 10,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 20,
                100.mbitsPerSecond to 60,
                1000.mbitsPerSecond to 20,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 33,
                100.mbitsPerSecond to 33,
                1000.mbitsPerSecond to 33,
            ),
        )
    }
}