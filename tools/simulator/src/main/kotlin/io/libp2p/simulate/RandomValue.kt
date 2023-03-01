package io.libp2p.simulate

import java.util.Random
import kotlin.time.Duration.Companion.milliseconds

fun interface RandomValue<T> {

    fun next(): T

    companion object {
        fun <T> const(constVal: T) = object : RandomValue<T> {
            override fun next() = constVal
        }
        fun uniform(from: Double, to: Double, rnd: Random) = object : RandomValue<Double> {
            override fun next() = from + rnd.nextDouble() * (to - from)
        }
    }
}

fun interface RandomDistribution<T> {
    fun newValue(rnd: Random): RandomValue<T>

    companion object {
        fun <T> const(constVal: T) = ConstRandomDistr(constVal)
        fun uniform(from: Double, to: Double) = UniformRandomDistr(from, to)
        fun uniform(from: Long, toExclusive: Long) =
            uniform(from.toDouble(), toExclusive.toDouble())
                .map { it.toLong() }
    }

    data class ConstRandomDistr<T>(val constVal: T) : RandomDistribution<T> {
        override fun newValue(rnd: Random) = RandomValue.const(constVal)
    }
    data class UniformRandomDistr(val from: Double, val to: Double) : RandomDistribution<Double> {
        override fun newValue(rnd: Random) = RandomValue.uniform(from, to, rnd)
    }
}

fun <T, R> RandomDistribution<T>.map(mapper: (T) -> R): RandomDistribution<R> =
    RandomDistribution {
        this.newValue(it).map(mapper)
    }

fun <T, R> RandomValue<T>.map(mapper: (T) -> R): RandomValue<R> =
    RandomValue {
        mapper(this.next())
    }

fun RandomDistribution<Long>.milliseconds() = this.map { it.milliseconds }