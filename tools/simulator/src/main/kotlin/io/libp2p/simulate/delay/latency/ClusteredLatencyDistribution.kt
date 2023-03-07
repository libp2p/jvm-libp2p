package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.RandomValue
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import kotlin.time.Duration

typealias ClusterId = Int

class ClusteredLatencyDistribution(
    val clusterLatencySupplier: (ClusterId) -> RandomDistribution<Duration>,
    val interClusterLatencySupplier: (ClusterId) -> RandomDistribution<Duration>,
    val clusterFunction: (SimPeer) -> ClusterId
) : LatencyDistribution {

    override fun getLatency(connection: SimConnection): RandomDistribution<Duration> {
        val p1Cluster = clusterFunction(connection.dialer)
        val p2Cluster = clusterFunction(connection.listener)
        return if (p1Cluster == p2Cluster) {
            interClusterLatencySupplier(p1Cluster)
        } else {
            clusterLatencySupplier(p1Cluster) + clusterLatencySupplier(p2Cluster)
        }
    }

    private companion object {
        operator fun RandomValue<Duration>.plus(other: RandomValue<Duration>) = RandomValue {
            this.next() + other.next()
        }

        operator fun RandomDistribution<Duration>.plus(other: RandomDistribution<Duration>) =
            RandomDistribution { random ->
                this.newValue(random) + other.newValue(random)
            }
    }
}