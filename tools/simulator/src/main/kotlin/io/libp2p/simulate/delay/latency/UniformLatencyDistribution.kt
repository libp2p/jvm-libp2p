package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.SimConnection
import kotlin.time.Duration

class UniformLatencyDistribution(
    val latency: RandomDistribution<Duration>
) : LatencyDistribution {
    override fun getLatency(connection: SimConnection): RandomDistribution<Duration> = latency
}