package io.libp2p.simulate

import io.libp2p.simulate.util.gcd
import io.libp2p.simulate.util.infiniteLoopIterator
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun interface RandomValue<T> {

    fun next(): T

    companion object {
        fun <T> const(constVal: T) =
            RandomValue { constVal }

        fun uniform(from: Double, to: Double, rnd: Random) =
            RandomValue { from + rnd.nextDouble() * (to - from) }
    }
}

fun interface RandomDistribution<T> {
    fun newValue(rnd: Random): RandomValue<T>

    companion object {
        fun <T> const(constVal: T) = RandomDistribution {
            RandomValue.const(constVal)
        }.named(constVal.toString())

        fun uniform(from: Double, to: Double) = RandomDistribution {
            RandomValue.uniform(from, to, it)
        }.named("[$from, $to)")

        fun uniform(from: Long, toExclusive: Long) = RandomDistribution {
            RandomValue.uniform(from.toDouble(), toExclusive.toDouble(), it)
                .map { it.toLong() }
        }.named("[$from, $toExclusive)")

        /**
         * Not really a random discrete distribution
         * Tries to distribute values according to their probabilities (in %%) as even as possible
         */
        fun <T> discreteEven(valuesToPercentages: Collection<Pair<T, Int>>): RandomDistribution<T> {
            val percentages = valuesToPercentages.map { it.second }
            val percentageGcd = gcd(percentages)
            val occurrences = valuesToPercentages
                .flatMap { (value, percentage) ->
                    val occurrenceCount = percentage / percentageGcd
                    List(occurrenceCount) { value }
                }
            val name = valuesToPercentages.joinToString("/") { it.first.toString() } +
                    " at " + percentages.joinToString("/") + " %"

            return RandomDistribution { random ->
                occurrences
                    .shuffled(random)
                    .infiniteLoopIterator()
                    .asRandomValue()
            }.named(name)
        }

        fun <T> discreteEven(vararg valuesToPercentages: Pair<T, Int>) =
            discreteEven(valuesToPercentages.toList())
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

fun <T> RandomDistribution<T>.named(name: String): RandomDistribution<T> =
    object : RandomDistribution<T> {
        override fun newValue(rnd: Random): RandomValue<T> =
            this@named.newValue(rnd)

        override fun toString(): String = name
    }

operator fun RandomValue<Duration>.plus(other: RandomValue<Duration>) = RandomValue {
    this.next() + other.next()
}

operator fun RandomDistribution<Duration>.plus(other: RandomDistribution<Duration>) =
    RandomDistribution { random ->
        this.newValue(random) + other.newValue(random)
    }

fun RandomDistribution<Long>.milliseconds() = this.map { it.milliseconds }

fun <T> Iterator<T>.asRandomValue() = RandomValue { this.next() }