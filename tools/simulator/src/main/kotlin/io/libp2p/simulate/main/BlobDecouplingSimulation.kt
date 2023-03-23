package io.libp2p.simulate.main

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.choke.ChokeStrategyPerTopic
import io.libp2p.pubsub.gossip.choke.SimpleTopicChokeStrategy
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.bandwidthDistribution
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.gossip.GossipSimulation
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.main.scenario.Decoupling
import io.libp2p.simulate.main.scenario.MaliciousPeerManager
import io.libp2p.simulate.main.scenario.ResultPrinter
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.stats.GroupByRangeAggregator
import io.libp2p.simulate.stats.Stats
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.util.ReadableSize
import io.libp2p.simulate.util.byIndexes
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.simulate.util.countValues
import io.libp2p.tools.log
import java.security.DrbgParameters.NextBytes
import java.util.Random
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    BlobDecouplingSimulation().runAndPrint()
}


class BlobDecouplingSimulation(
    val nodeCount: Int = 1000,
    val nodePeerCount: Int = 30,
    val randomSeed: Long = 0L,
    val testMessageCount: Int = 5,
    val floodPublish: Boolean = false,

    val sendingPeerBandwidth: Bandwidth = 100.mbitsPerSecond,
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> =
//        listOf(RandomDistribution.const(sendingPeerBandwidth)),
        bandwidthDistributions.byIndexes(2),
    val decouplingParams: List<Decoupling> = listOf(
        Decoupling.Coupled,
        Decoupling.DecoupledManyTopics,
//        Decoupling.DecoupledSingleTopic,
    ),
    val latencyParams: List<LatencyDistribution> =
        listOf(
            LatencyDistribution.createUniformConst(1.milliseconds, 50.milliseconds)
//            LatencyDistribution.createConst(10.milliseconds),
//            LatencyDistribution.createConst(50.milliseconds),
//            LatencyDistribution.createConst(100.milliseconds),
//            LatencyDistribution.createConst(150.milliseconds),
//            LatencyDistribution.createConst(200.milliseconds),
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
            RandomDistribution.const(10.milliseconds),
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

    val blockConfigs: List<BlockConfig> = listOf(
        BlockConfig.ofKilobytes(128, 128, 4)
    ),

    val paramsSet: List<SimParams> =
        cartesianProduct(
            bandwidthsParams,
            latencyParams,
            validationDelayParams,
            decouplingParams,
            blockConfigs
        ) {
            SimParams(it.first, it.second, it.third, it.fourth, it.fifth)
        },

    ) {

    data class BlockConfig(
        val blockSize: Int,
        val blobSize: Int,
        val blobCount: Int,
    ) {
        override fun toString() =
            "BlockConfig[${ReadableSize.create(blockSize)} + ${ReadableSize.create(blobSize)} * $blobCount]"

        companion object {
            fun ofKilobytes(blockKBytes: Int, blobKBytes: Int, blobCount: Int) =
                BlockConfig(blockKBytes * 1024, blobKBytes * 1024, blobCount)
        }
    }

    data class SimParams(
        val bandwidth: RandomDistribution<Bandwidth>,
        val latency: LatencyDistribution,
        val validationDelays: RandomDistribution<Duration>,
        val decoupling: Decoupling,
        val blockConfig: BlockConfig
    )

    data class RunResult(
        val messages: GossipMessageResult
    ) {
        val deliveryResult =
            messages.getGossipPubDeliveryResult().aggregateSlowestByPublishTime()
    }

    fun createBlobScenario(simParams: SimParams, logger: SimulationLogger = { log(it) }): BlobDecouplingScenario =
        BlobDecouplingScenario(
            logger = logger,
            blockSize = simParams.blockConfig.blockSize,
            blobSize = simParams.blockConfig.blobSize,
            blobCount = simParams.blockConfig.blobCount,

            sendingPeerBand = sendingPeerBandwidth,
            messageCount = testMessageCount,
            nodeCount = nodeCount,
            nodePeerCount = nodePeerCount,
            peerBands = simParams.bandwidth,
            latency = simParams.latency,
            gossipParams = Eth2DefaultGossipParams.copy(
                floodPublish = floodPublish,
            ),
            peerMessageValidationDelays = simParams.validationDelays,
        )

    fun runAndPrint() {
        val results = SimulationRunner<SimParams, RunResult> { params, logger ->
            run(params, logger)
        }.runAll(paramsSet)
        printResults(paramsSet.zip(results).toMap())
    }

    private fun printResults(res: Map<SimParams, RunResult>) {
        val printer = ResultPrinter(res).apply {
            addNumberStats { it.deliveryResult.deliveryDelays }
                .apply {
                    addGeneric("count") { it.size }
                    addLong("min") { it.min }
                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addMetric("msgCount") { it.messages.getTotalMessageCount() }
            addMetric("traffic") { it.messages.getTotalTraffic() }
            addMetric("publishCount") { it.messages.publishMessages.size }
            addMetric("iWantCount") { it.messages.iWantMessages.size }
            addMetric("iWantDeliveryCount") { res ->
                res.deliveryResult.deliveries
                    .count { res.messages.isByIWantPubMessage(it.origGossipMsg) }
            }
            addMetric("iWantPublishCount") { res ->
                res.messages.publishMessages
                    .count { res.messages.isByIWantPubMessage(it) }
            }
        }

        log("Results:")
        println(printer.printPretty())
//        println()
//        println("Ranged delays:")
//        println("======================")
//        println(printer
//            .createRangedLongStats { it.deliveryResult.deliveryDelays }
//            .apply {
//                minValue = 0
//                rangeSize = 50
//            }
//            .printTabSeparated()
//        )
        log("Done.")
    }

    fun run(params: SimParams, logger: SimulationLogger): RunResult {
        val scenario = createBlobScenario(params, logger)

        scenario.simulation.clearAllMessages()

        logger("Sending test messages...")
        scenario.test(params.decoupling, testMessageCount)

        return RunResult(scenario.simulation.gossipMessageCollector.gatherResult())
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
