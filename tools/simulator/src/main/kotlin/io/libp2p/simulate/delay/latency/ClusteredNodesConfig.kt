package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.RandomValue
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.milliseconds
import kotlin.time.Duration

class ClusteredNodesConfig<TCluster>(
    val clusterNodesDistribution: RandomValue<TCluster>,
    val interClusterMinLatencyFun: (TCluster, TCluster) -> Duration,
    val dispersionPercents: Int
) {

    private val nodeClusters = mutableListOf<TCluster>()

    private fun fillNodeClusters(endIndexInclusive: Int) {
        (nodeClusters.size .. endIndexInclusive).forEach {
            nodeClusters += clusterNodesDistribution.next()
        }
    }

    fun getCluster(node: SimPeer): TCluster {
        fillNodeClusters(node.simPeerId)
        return nodeClusters[node.simPeerId]
    }

    val latencyDistribution: LatencyDistribution = ClusteredLatencyDistribution(
        { c1, c2 ->
            val minLatencyMs = interClusterMinLatencyFun(c1, c2).inWholeMilliseconds
            RandomDistribution.uniform(minLatencyMs, minLatencyMs * (100 + dispersionPercents) / 100).milliseconds()
        },
        { getCluster(it) }
    )
}