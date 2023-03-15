package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.RandomValue
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.sampleValue
import java.util.Random
import kotlin.time.Duration

data class RandomConstLatencyDistribution(
    val latency: RandomDistribution<Duration>
) : LatencyDistribution {
    override fun getLatency(connection: SimConnection, rnd: Random): RandomValue<Duration> =
        RandomValue.const(latency.sampleValue(rnd))

    override fun toString(): String = latency.toString()
}