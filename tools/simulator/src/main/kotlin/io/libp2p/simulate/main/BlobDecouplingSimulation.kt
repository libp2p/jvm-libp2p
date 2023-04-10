package io.libp2p.simulate.main

import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.main.BlobDecouplingSimulation.MessageChoke.*
import io.libp2p.simulate.main.EpisubSimulation.Companion.awsLatencyDistribution
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.main.scenario.Decoupling
import io.libp2p.simulate.main.scenario.ResultPrinter
import io.libp2p.simulate.stats.collect.gossip.*
import io.libp2p.simulate.util.ReadableSize
import io.libp2p.simulate.util.byIndexes
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.tools.log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    BlobDecouplingSimulation().runAndPrint()
}

@Suppress("UNUSED_VARIABLE")
class BlobDecouplingSimulation(
    val nodeCount: Int = 1000,
    val nodePeerCount: Int = 30,
    val randomSeed: Long = 0L,
    val testMessageCount: Int = 5,
    val floodPublish: Boolean = false,

    val sendingPeerBandwidth: Bandwidth = 100.mbitsPerSecond,
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> =
//        listOf(RandomDistribution.const(sendingPeerBandwidth)),
        bandwidthDistributions.byIndexes(0, 1, 2),
    val decouplingParams: List<Decoupling> = listOf(
        Decoupling.Coupled,
        Decoupling.DecoupledManyTopics,
//        Decoupling.DecoupledSingleTopic,
    ),
    val latencyParams: List<LatencyDistribution> =
        listOf(
//            LatencyDistribution.createUniformConst(1.milliseconds, 50.milliseconds)
            LatencyDistribution.createConst(10.milliseconds),
//            LatencyDistribution.createConst(50.milliseconds),
//            LatencyDistribution.createConst(100.milliseconds),
//            LatencyDistribution.createConst(150.milliseconds),
//            LatencyDistribution.createConst(200.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 20.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 50.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 100.milliseconds),
            LatencyDistribution.createUniformConst(10.milliseconds, 200.milliseconds),
            awsLatencyDistribution
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

    val chokeMessageParams: List<MessageChoke> = listOf(
        None,
        OnReceive,
        OnNotify
    ),

    val paramsSet: List<SimParams> =
        cartesianProduct(
            decouplingParams,
            bandwidthsParams,
            latencyParams,
            validationDelayParams,
            blockConfigs,
            chokeMessageParams
        ) {
            SimParams(it.first, it.second, it.third, it.fourth, it.fifth, it.sixth)
        },

    ) {

    enum class MessageChoke { None, OnReceive, OnNotify }

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
        val decoupling: Decoupling,
        val bandwidth: RandomDistribution<Bandwidth>,
        val latency: LatencyDistribution,
        val validationDelays: RandomDistribution<Duration>,
        val blockConfig: BlockConfig,
        val chokeMessage: MessageChoke
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
                chokeMessageEnabled = simParams.chokeMessage != None,
                sendingControlEnabled = simParams.chokeMessage == OnNotify
//                heartbeatInterval = 5.seconds
            ),
            peerMessageValidationDelays = simParams.validationDelays,
        )

    fun runAndPrint() {
        val results = SimulationRunner<SimParams, RunResult1> { params, logger ->
            run(params, logger)
        }.runAll(paramsSet)
        printResults(paramsSet.zip(results).toMap())
    }

    interface NumberSeries<TNum: Number> {
        val numbers: List<TNum>
    }

    class LongSeries(
        override val numbers: List<Long>
    ) : NumberSeries<Long>

    class RunResult1(
        messages: GossipMessageResult,
        deliveryResult: GossipPubDeliveryResult =
            messages.getGossipPubDeliveryResult().aggregateSlowestByPublishTime()
    ) {
        val deliveryDelays = LongSeries(deliveryResult.deliveryDelays)

        val msgCount = messages.getTotalMessageCount()
        val traffic = messages.getTotalTraffic()
        val pubCount = messages.publishMessages.size
        val iWants = messages.iWantRequestCount
        val iWantDeliv = deliveryResult.getDeliveriesByIWant(messages).size
        val iWantPub = messages.publishesByIWant.size
        val dupPub = messages.duplicatePublishes.size
        val roundPub = messages.roundPublishes.size
    }

    private fun printResults(res: Map<SimParams, RunResult1>) {
        val printer = ResultPrinter(res).apply {
            addNumberStats { it.deliveryDelays.numbers }
                .apply {
                    addGeneric("count") { it.size }
                    addLong("min") { it.min }
                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addMetric("msgCount") { it.msgCount }
            addMetric("traffic") { it.traffic }
            addMetric("pubCount") { it.pubCount }
            addMetric("iWants") { it.iWants }
            addMetric("iWantDeliv") { it.iWantDeliv }
            addMetric("iWantPub") { it.iWantPub }
            addMetric("dupPub") { it.dupPub }
            addMetric("roundPub") { it.roundPub }
        }

        log("Results:")
        println(printer.printPretty())
        println()
        println(printer.printTabSeparated())
        println()
        println("Ranged delays:")
        println("======================")
        println(printer
            .createRangedLongStats { it.deliveryDelays.numbers }
            .apply {
                minValue = 0
                rangeSize = 50
            }
            .printTabSeparated()
        )

        log("Done.")
    }

    private fun tempResults(res: GossipMessageResult) {
        val publishIWants = res.publishMessages
            .associateWith {
                res.getIWantsForPubMessage(it)
            }
            .filterValues { it.isNotEmpty() }
//            .onEach {
//                if (it.value.size > 1) {
//                    println("Many IWants for a singe publish: ${it.value}, ${it.key}")
//                }
//            }
//            .flatMap { it.value }

        val missedIWants =
            res.iWantMessages.toSet() - publishIWants.flatMap { it.value }.toSet()


        val connectionMessages =
            res.getConnectionMessages(res.allPeersById[822]!!, res.allPeersById[41]!!)
        val peerMessages = res.getPeerMessages(res.allPeersById[41]!!)

        data class MessagePublishKey(
            val messageId: SimMessageId,
            val fromPeer: SimPeer,
            val toPeer: SimPeer
        )

        val duplicatePublish = res.duplicatePublishes
        val roundPublish = res.roundPublishes

        println("Duplicate publishes: ${duplicatePublish.size}")
    }

    fun run(params: SimParams, logger: SimulationLogger): RunResult1 {
        val scenario = createBlobScenario(params, logger)

        scenario.simulation.clearAllMessages()

        logger("Sending test messages...")
        scenario.test(params.decoupling, testMessageCount)
        val messageResult = scenario.simulation.gossipMessageCollector.gatherResult()

//        tempResults(messageResult)

        return RunResult1(messageResult)
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
