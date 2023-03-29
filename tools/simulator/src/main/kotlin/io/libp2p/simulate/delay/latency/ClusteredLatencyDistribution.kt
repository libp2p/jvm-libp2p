package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.RandomValue
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import java.util.Random
import kotlin.time.Duration

class ClusteredLatencyDistribution<ClusterId>(
    val interClusterLatencySupplier: (ClusterId, ClusterId) -> RandomDistribution<Duration>,
    val clusterFunction: (SimPeer) -> ClusterId
) : LatencyDistribution {

    override fun getLatency(connection: SimConnection, rnd:Random): RandomValue<Duration> {
        synchronized(this) {
            val p1Cluster = clusterFunction(connection.dialer)
            val p2Cluster = clusterFunction(connection.listener)
            return interClusterLatencySupplier(p1Cluster, p2Cluster).newValue(rnd)
        }
    }
}