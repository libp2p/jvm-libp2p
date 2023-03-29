package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.*
import java.util.Random
import kotlin.time.Duration

fun interface LatencyDistribution {

    fun getLatency(connection: SimConnection, rnd: Random): RandomValue<Duration>

    companion object {

        fun createConst(latency: Duration): LatencyDistribution =
            createRandomConst(RandomDistribution.const(latency))

        fun createUniformConst(from: Duration, to: Duration): LatencyDistribution =
            createRandomConst(
                RandomDistribution
                    .uniform(from.inWholeMilliseconds, to.inWholeMilliseconds)
                    .milliseconds()
                    .named("[$from, $to)")
            )

        fun createRandomConst(distrib: RandomDistribution<Duration>): LatencyDistribution =
            RandomConstLatencyDistribution(distrib)
    }
}

fun LatencyDistribution.named(name: String): LatencyDistribution =
    object : LatencyDistribution {
        override fun getLatency(connection: SimConnection, rnd: Random): RandomValue<Duration> =
            this@named.getLatency(connection, rnd)

        override fun toString(): String {
            return name
        }
    }