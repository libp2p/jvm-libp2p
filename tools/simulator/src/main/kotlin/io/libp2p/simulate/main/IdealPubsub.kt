package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.IdealPubsub.SendParams
import io.libp2p.simulate.main.IdealPubsub.SendType.*
import io.libp2p.simulate.main.scenario.ResultPrinter
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.tools.log
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

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

    val sendTypeParams: List<SendParams> = listOf(
        SendParams.sequential(),
        SendParams.decoupled(2),
        SendParams.decoupled(4),
        SendParams.decoupled(8),
        SendParams.decoupled(16),
        SendParams.decoupled(64),
        SendParams.decoupled(256),
        SendParams.decoupled(1024),
        SendParams.decoupledAbsolute(),
        SendParams.parallel(2),
        SendParams.parallel(4),
        SendParams.parallel(8),
    ),
//    val messageSizeParams: List<Long> = listOf(5 * 128 * 1024),
    val messageSizeParams: List<Long> = listOf(1 * 1024 * 1024),
    val nodeCountParams: List<Int> = listOf(10000),
    val maxSentParams: List<Int> = listOf(Int.MAX_VALUE),

    val paramsSet: List<IdealPubsub.SimParams> =
        cartesianProduct(
            bandwidthParams,
            latencyParams,
            sendTypeParams,
            nodeCountParams,
            maxSentParams
        ) {
            IdealPubsub.SimParams(it.first, it.second, it.third, messageSizeParams[0], it.fourth, it.fifth)
        },
) {

    data class RunResult(
        val nodes: List<IdealPubsub.Node>
    )

    fun runAndPrint() {
        val results =
            SimulationRunner<IdealPubsub.SimParams, RunResult>(/*threadCount = 1*/) { params, logger ->
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

        log("Done.")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IdealPubsub(
    val params: SimParams
) {
    enum class SendType { Sequential, Parallel, Decoupled }

    class SendParams(
        val type: SendType,
        private val count: Int
    ) {
        val parallelCount get() = if (type == Parallel) count else 1
        val decoupledChunkCount get() = if (type == Decoupled) count else 1
        val absoluteDecoupled get() = type == Decoupled && count == Int.MAX_VALUE

        override fun toString(): String {
            val countStr = when {
                type == Sequential -> ""
                absoluteDecoupled -> "-inf"
                else -> "-$count"
            }
            return "$type$countStr"
        }

        companion object {
            fun sequential() = SendParams(Sequential, 1)
            fun decoupled(chunkCount: Int) = SendParams(Decoupled, chunkCount)
            fun decoupledAbsolute() = SendParams(Decoupled, Int.MAX_VALUE)
            fun parallel(parallelSendCount: Int) = SendParams(Parallel, parallelSendCount)
        }
    }

    data class SimParams(
        val bandwidth: Bandwidth,
        val latency: Duration,
        val sendParams: SendParams,
        val messageSize: Long,
        val nodeCount: Int,
        val maxSent: Int = Int.MAX_VALUE,
    )

    val result: List<Node> by lazy {
        simulate()
    }


    private val scope = TestScope()
    private var counter = 0
    private val nodes = mutableListOf<Node>()

    inner class Node(
        val hop: Int,
    ) {
        var sentCount: Int = 0
        val number: Int = counter++
        var deliverTime: Long = -1

        private fun acquireNode(): Node {
            val newNode = Node(hop + 1)
            sentCount++
            nodes += newNode
            return newNode
        }


        suspend fun deliver() {
            deliverTime = scope.currentTime

            // time to transmit a single message through the node bandwidth
            val throughputDuration = params.bandwidth.getTransmitTime(params.messageSize)
            // time to transmit a number of messages in parallel through the node bandwidth
            val parallelThroughputDuration = throughputDuration * params.sendParams.parallelCount

            val firstThroughputDuration =
                when {
                    hop == 0 -> parallelThroughputDuration
                    params.sendParams.absoluteDecoupled -> ZERO
                    else -> parallelThroughputDuration / params.sendParams.decoupledChunkCount
                }

            // simulate the first message throughput delay
            delay(firstThroughputDuration)

            broadcast@ while (true) {
                for (i in 0 until params.sendParams.parallelCount) {
                    if (sentCount == params.maxSent || nodes.size == params.nodeCount)
                        break@broadcast

                    val receivingNode = acquireNode()
                    scope.launch {
                        // simulate `latency`
                        delay(params.latency)
                        // `receivingNode` received a message and starts broadcasting it
                        // asynchronously
                        receivingNode.deliver()
                    }
                }
                // simulate throughput delay
                delay(parallelThroughputDuration)
            }
        }
    }

    private fun simulate(): List<Node> {
        scope.runTest {
            val publishNode = Node(0)
            nodes += publishNode
            publishNode.deliver()
        }
        return nodes
    }
}