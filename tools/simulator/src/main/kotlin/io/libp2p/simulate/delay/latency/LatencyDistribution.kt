package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.SimConnection
import kotlin.time.Duration

fun interface LatencyDistribution {

    fun getLatency(connection: SimConnection): RandomDistribution<Duration>

    companion object {

        fun createConst(latency: Duration) =
            UniformLatencyDistribution(RandomDistribution.const(latency))

        fun createUniform(latency: RandomDistribution<Duration>) =
            UniformLatencyDistribution(latency)
    }
}

fun LatencyDistribution.named(name: String): LatencyDistribution =
    object : LatencyDistribution {
        override fun getLatency(connection: SimConnection): RandomDistribution<Duration> =
            this@named.getLatency(connection)

        override fun toString(): String {
            return name
        }
    }