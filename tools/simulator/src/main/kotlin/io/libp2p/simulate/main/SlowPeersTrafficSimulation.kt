package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.util.*

fun main() {
    SlowPeersTrafficSimulation().runAndPrint()
}

class SlowPeersTrafficSimulation(
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> =
        BlobDecouplingSimulation.bandwidthDistributions
            .byIndexes(1, 2, 3),
    val decouplingParams: List<Decoupling> = listOf(
        Decoupling.Coupled,
        Decoupling.FullyDecoupled
    ),
    val paramsSet: List<SimParams> =
        cartesianProduct(bandwidthsParams, decouplingParams) { SimParams(it.first, it.second) }
) {

    enum class Decoupling{ Coupled, FullyDecoupled }

    data class SimParams(
        val bandwidths: RandomDistribution<Bandwidth>,
        val decoupling: Decoupling
    ) {
        override fun toString() = "$bandwidths, $decoupling"
    }

    data class RunResult(
        // average inbound traffic per peer
        val inboundTrafficByBandwidth: Map<Bandwidth, Long>
    )

    fun createBlobScenario(simParams: SimParams): BlobDecouplingScenario {
        return BlobDecouplingScenario(
//                logger = {},
            messageCount = 10,
            nodeCount = 500,
            peerBands = simParams.bandwidths,
            gossipParams = Eth2DefaultGossipParams.copy(
                floodPublish = false
            )
//                randomSeed = 2
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
        when (params.decoupling) {
            Decoupling.Coupled -> scenario.testCoupled()
            Decoupling.FullyDecoupled -> scenario.testAllDecoupled()
        }
        val messageResult = scenario.simulation.gossipMessageCollector.gatherResult()
        return calcResult(messageResult)
    }

    fun calcResult(res: GossipMessageResult): RunResult {
        val bandwidthPeerCount = res.allPeers
            .groupingBy { it.inboundBandwidth.totalBandwidth }
            .eachCount()

        return res
            .groupBy { it.receivingPeer.inboundBandwidth.totalBandwidth }
            .mapValues { (band, inbounds) ->
                inbounds.getTotalTraffic() / bandwidthPeerCount[band]!!
            }
            .toSortedMap()
            .let { RunResult(it) }
    }

    fun printResults(runs: Map<SimParams, RunResult>) {
        val table = Table(runs.mapValues { it.value.inboundTrafficByBandwidth })
        println("Pretty results:")
        println(table.printPretty().prependIndent("  "))
        println("\n\nTab separated results:")
        println("======================")
        println(table.print())
    }
}