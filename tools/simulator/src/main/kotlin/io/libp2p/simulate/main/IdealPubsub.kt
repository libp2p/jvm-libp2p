package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
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

    val sendTypeParams: List<IdealPubsub.SendType> = listOf(
        Sequential,
        Parallel2,
        Parallel4,
        Parallel8,
        Decoupled
    ),
    val messageSizeParams: List<Long> = listOf(5 * 128 * 1024),
    val nodeCountParams: List<Int> = listOf(10000),
    val maxSentParams: List<Int> = listOf(8),

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
    enum class SendType { Sequential, Parallel2, Parallel4, Parallel8, Decoupled }

    data class SimParams(
        val bandwidth: Bandwidth,
        val latency: Duration,
        val sendType: SendType,
        val messageSize: Long,
        val nodeCount: Int,
        val maxSent: Int = Int.MAX_VALUE,
    )

    val result: List<Node> by lazy {
        simulate()
    }

    private val SendType.parallelCount
        get() =
            when (this) {
                Sequential -> 1
                Parallel2 -> 2
                Parallel4 -> 4
                Parallel8 -> 8
                Decoupled -> 1
            }

    private var counter = 0
    private val nodes = mutableListOf<Node>()

    inner class Node(
        val scope: TestScope,
        val hop: Int,
        val fromNode: Int,
        var sentCount: Int = 0,
        val number: Int = counter++,
    ) {
        var deliverTime: Long = -1

        private fun newNodeSent(): Node {
            val newNode = Node(scope, hop + 1, number)
            sentCount++
            nodes += newNode
            return newNode
        }


        suspend fun startBroadcasting() {
            deliverTime = scope.currentTime

            // time to transmit a single message through the node bandwidth
            val messageTransmitDuration = params.bandwidth.getTransmitTime(params.messageSize)
            // time to transmit a number of messages in parallel through the node bandwidth
            val messageParallelTransmitDuration = messageTransmitDuration * params.sendType.parallelCount

            if (params.sendType == Decoupled && hop > 0) {
                // when send type is Decoupled a node 'streams' a message in parallel while receiving it
                // thus the message is already sent to the _first_ peer and the initial delay is 0
                // the publishing node (hop == 0) still needs time to transmit a message to the first peer
            } else {
                // sending the message to the first peers(s)
                delay(messageParallelTransmitDuration)
            }

            broadcast@ while (true) {
                for (i in 0 until params.sendType.parallelCount) {
                    if (sentCount == params.maxSent || nodes.size == params.nodeCount)
                        break@broadcast

                    val newNode = newNodeSent()
                    scope.launch {
                        // delay by latency: time the message 'flies over network' to the `newNode`
                        // + message validation time
                        delay(params.latency)
                        // now `newNode` received the message and starts broadcasting it to its peers
                        newNode.startBroadcasting()
                    }
                }
                // sending the message to the next peers(s)
                delay(messageParallelTransmitDuration)
            }
        }
    }

    private fun simulate(): List<Node> {
        runTest {
            val publishNode = Node(this, 0, -1)
            nodes += publishNode
            publishNode.startBroadcasting()
        }
        return nodes
    }
}