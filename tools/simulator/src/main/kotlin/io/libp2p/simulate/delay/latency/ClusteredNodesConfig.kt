package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.milliseconds
import kotlin.time.Duration

class ClusteredNodesConfig<TCluster>(
    val clusterNodeCounts: List<Pair<TCluster, Int>>,
    val interClusterMinLatencyFun: (TCluster, TCluster) -> Duration,
    val dispersionPercents: Int
) {

    val clusterSizes = clusterNodeCounts.map { it.second }
    private val clusterIndexRanges = clusterSizes
        .scan(0) { acc, i -> acc + i}
        .dropLast(1)
        .zip(clusterSizes) { offset, size -> offset until offset + size}

    fun getClusterIndex(node: SimPeer): Int = clusterIndexRanges.indexOfFirst { node.simPeerId in it }
    fun getCluster(node: SimPeer): TCluster = clusterNodeCounts[getClusterIndex(node)].first

    val totalNodeCount = clusterSizes.sum()

    val latencyDistribution = ClusteredLatencyDistribution(
        { c1, c2 ->
            val minLatencyMs = interClusterMinLatencyFun(c1, c2).inWholeMilliseconds
            RandomDistribution.uniform(minLatencyMs, minLatencyMs * (100 + dispersionPercents) / 100).milliseconds()
        },
        { getCluster(it) }
    )
}