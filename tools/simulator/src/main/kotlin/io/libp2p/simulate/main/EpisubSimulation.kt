package io.libp2p.simulate.main

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.choke.ChokeStrategyPerTopic
import io.libp2p.pubsub.gossip.choke.SimpleTopicChokeStrategy
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.bandwidthDistribution
import io.libp2p.simulate.delay.latency.ClusteredNodesConfig
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.delay.latency.aws.AwsLatencies
import io.libp2p.simulate.delay.latency.aws.AwsRegion
import io.libp2p.simulate.delay.latency.named
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.gossip.GossipSimulation
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.util.Table
import io.libp2p.simulate.util.byIndexes
import io.libp2p.simulate.util.cartesianProduct
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    EpisubSimulation().runAndPrint()
}

class EpisubSimulation(
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> = bandwidthDistributions
        .byIndexes(0)
   ,
    val decouplingParams: List<Decoupling> = listOf(
//        Decoupling.Coupled,
        Decoupling.FullyDecoupled
    ),
    val meshParams: List<MeshSimParams> = listOf(
        MeshSimParams(PubsubProtocol.Gossip_V_1_1, 6),
        MeshSimParams(PubsubProtocol.Gossip_V_1_2, Eth2DefaultGossipParams.D),
    ),

    val latencyParams: List<LatencyDistribution> =
//        listOf(LatencyDistribution.createConst(10.milliseconds)),
        latencyDistributions.byIndexes(0),

    val validationDelayParams: List<RandomDistribution<Duration>> =
//        listOf(RandomDistribution.const(10.milliseconds)),
        validatioDelayDistributions.byIndexes(0),

    val paramsSet: List<SimParams> =
        cartesianProduct(bandwidthsParams, validationDelayParams, decouplingParams, meshParams) {
            SimParams(it.first, it.second, latencyParams[0], it.third, it.fourth.gossipVersion, it.fourth.D)
        },
    val chokeWarmupMessageCount: Int = 10,
    val testMessageCount: Int = 20
) {

    enum class Decoupling { Coupled, FullyDecoupled }

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
        val D: Int
    ) {
        override fun toString() = "$bandwidths, $decoupling, ${gossipVersion.version}, $D"
    }

    data class PeerChokeResult(
        val peer: GossipSimPeer,
        val meshSize: Int,
        val chokedCount: Int,
        val chokedByCount: Int
    )

    data class TopicChokeResult(
        val peerResults: List<PeerChokeResult>
//        val meshCounts: List<Int>,
//        val chokedCounts: List<Int>,
//        val chokedByCounts: List<Int>,
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
        val chokeResult: ChokeResult,
        val networkResult: NetworkResult
    )

    fun createBlobScenario(simParams: SimParams): BlobDecouplingScenario {
        val nodeCount = 1000
        return BlobDecouplingScenario(
//                logger = {},
            messageCount = 1,
            nodeCount = nodeCount,
            peerBands = simParams.bandwidths,
            gossipParams = Eth2DefaultGossipParams.copy(
                floodPublish = false,
                D = simParams.D,
                DLow = simParams.D - 2,
                DHigh = simParams.D + 2
            ),
            peerMessageValidationDelays = run {
                val delays = simParams.validationDelays.newValue(Random(1))
                List(nodeCount) { delays.next() }
            },
            gossipProtocol = simParams.gossipVersion,
            routerFactory = {
                SimGossipRouterBuilder().also {
                    it.chokeStrategy = ChokeStrategyPerTopic { SimpleTopicChokeStrategy(it) }
                }
            }
        )
    }

    fun runAndPrint() {
        val results = run(paramsSet)
        printResults(paramsSet.zip(results).toMap())
    }

    fun run(paramsSet: List<SimParams>): List<RunResult> =
        paramsSet.map { run(it) }

    fun run(params: SimParams): RunResult {
        val scenario = createBlobScenario(params)
        fun run(msgCount: Int) = when (params.decoupling) {
            Decoupling.Coupled -> scenario.testCoupled(msgCount)
            Decoupling.FullyDecoupled -> scenario.testAllDecoupled(msgCount)
        }

        run(chokeWarmupMessageCount)

        val chokeResults = calcChokeResults(scenario.simulation)
        val tmp1 =
            if (params.gossipVersion.version == PubsubProtocol.Gossip_V_1_2.version) {
                scenario.peerMessageValidationDelays
                    .zip(chokeResults.topicResults.values.first().peerResults)
            } else {
                emptyList()
            }

        scenario.simulation.clearAllMessages()

        run(testMessageCount)

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
        return RunResult(
            messageResult.getGossipPubDeliveryResult()
                .aggregateSlowestBySimMessageId(messageGroups)
                .deliveryDelays,
            chokeResults,
            NetworkResult(messageResult.getTotalMessageCount(), messageResult.getTotalTraffic())
        )
    }

    fun printResults(runs: Map<SimParams, RunResult>) {
        fun delayStatsAsMap(delays: List<Long>): Map<String, Long> {
            val stats = StatsFactory.DEFAULT.createStats(delays)
            return mapOf(
                "count" to stats.getCount(),
                "min" to stats.getDescriptiveStatistics().min.toLong(),
                "50%" to stats.getDescriptiveStatistics().getPercentile(0.5).toLong(),
                "90%" to stats.getDescriptiveStatistics().getPercentile(0.9).toLong(),
                "max" to stats.getDescriptiveStatistics().max.toLong(),
            )
        }

        val tableDelays: Table<Any> = Table(runs.mapValues { (_, res) ->
            delayStatsAsMap(res.deliveryDelays)
        })
        val tableNetwork: Table<Any> = Table(runs.mapValues { (_, res) ->
            mapOf(
                "msgCount" to res.networkResult.messageCount,
                "traffic" to res.networkResult.traffic,
            )
        })
        val table = tableDelays.appendColumns(tableNetwork)

        println("Pretty results:")
        println(table.printPretty().prependIndent("  "))
        println("\n\nTab separated results:")
        println("======================")
        println(table.print())
    }

    companion object {
        val latencyDistributions = listOf(
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
        )

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