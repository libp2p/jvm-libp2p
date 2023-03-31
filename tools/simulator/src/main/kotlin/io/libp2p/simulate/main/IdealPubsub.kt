package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.scenario.ResultPrinter
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.simulate.util.delay
import io.libp2p.simulate.util.scheduleAtFixedRate
import io.libp2p.tools.log
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

fun main() {
    IdealPubsubSimulation().runAndPrint()
}

class IdealPubsubSimulation(
    val bandwidthParams: List<Bandwidth> = listOf(
        10.mbitsPerSecond,
        25.mbitsPerSecond,
        50.mbitsPerSecond,
        100.mbitsPerSecond,
        200.mbitsPerSecond,
        500.mbitsPerSecond,
        1024.mbitsPerSecond,
        (5 * 1024).mbitsPerSecond,
    ),
    val latencyParams: List<Duration> = listOf(
        0.milliseconds,
        1.milliseconds,
        5.milliseconds,
        10.milliseconds,
        20.milliseconds,
        50.milliseconds,
        100.milliseconds,
        200.milliseconds,
        500.milliseconds,
        1000.milliseconds,
    ),

    val parallelSends: List<Int> = listOf(1,2,3,4,8),
    val messageSizeParams: List<Long> = listOf(5 * 128 * 1024),
    val nodeCountParams: List<Int> = listOf(10000),
    val maxSentParams: List<Int> = listOf(8),

    val paramsSet: List<IdealPubsub.SimParams> =
        cartesianProduct(
            bandwidthParams,
            latencyParams,
            parallelSends,
            messageSizeParams,
            nodeCountParams,
            maxSentParams
        ) {
            IdealPubsub.SimParams(it.first, it.second, it.third, it.fourth, it.fifth, it.sixth)
        },
) {
    data class RunResult(
        val nodes: List<IdealPubsub.Node>
    )

    fun runAndPrint() {
        val results =
            SimulationRunner<IdealPubsub.SimParams, RunResult>(threadCount = 1) { params, logger ->
                run(params)
            }.runAll(paramsSet)
        printResults(paramsSet.zip(results).toMap())
    }

    fun run(params: IdealPubsub.SimParams): RunResult =
        RunResult(IdealPubsub(params).result)

    private fun printResults(res: Map<IdealPubsub.SimParams, RunResult>) {
        val printer = ResultPrinter(res).apply {
            addNumberStats("time") { it.nodes.drop(1).map { it.deliverTime } }
                .apply {
                    addLong("min") { it.min }
                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addNumberStats("hops") { it.nodes.map { it.hop } }
                .apply {
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addNumberStats("sent") { it.nodes.map { it.sentCount } }
                .apply {
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
        }

        log("Results:")
        println(printer.printPretty())
        println()
        println(printer.printTabSeparated())
        println()
        println("Ranged delays:")
        println("======================")
//        println(printer
//            .createRangedLongStats { it.deliveryDelays.numbers }
//            .apply {
//                minValue = 0
//                rangeSize = 50
//            }
//            .printTabSeparated()
//        )

        log("Done.")
    }

}

class IdealPubsub(
    val params: SimParams
) {
    data class SimParams(
        val bandwidth: Bandwidth,
        val latency: Duration,
        val parallelSends: Int,
        val messageSize: Long,
        val nodeCount: Int,
        val maxSent: Int = Int.MAX_VALUE
    )


    val messageTransmitDuration = params.bandwidth.getTransmitTime(params.messageSize)
    val messageParallelTransmitDuration = messageTransmitDuration * params.parallelSends

    val timeController = TimeControllerImpl()
    val executor = ControlledExecutorServiceImpl(timeController)

    val result: List<Node> by lazy {
        simulate()
    }

    var counter = 0

    inner class Node(
        val hop: Int,
        var sentCount: Int = 0,
        val number: Int = counter++,
        val deliverTime: Long = timeController.time
    ) {
        fun startBroadcasting() {
//            executor.delay(params.validationTime) {
                executor.scheduleAtFixedRate(messageParallelTransmitDuration, messageParallelTransmitDuration) {
                    if (sentCount < params.maxSent) {
                        sentCount += params.parallelSends
                        executor.delay(params.latency) {
                            repeat(params.parallelSends) {
                                newNode(hop + 1)
                            }
                        }
                    }
                    if (nodes.size == params.nodeCount) {
                        throw CancellationException()
                    }
                }
//            }
        }
    }

    private fun newNode(hop: Int): Node {
        if (nodes.size == params.nodeCount) {
            throw CancellationException()
        }
        val node = Node(hop)
        nodes += node
        node.startBroadcasting()
        return node
    }

    private val nodes = mutableListOf<Node>()

    private fun simulate(): List<Node> {
        val publishNode = newNode(0)
        publishNode.startBroadcasting()

        try {
            timeController.addTime(1.days.toJavaDuration())
        } catch (e: Exception) {
            // Done
        }

        return nodes
    }
}