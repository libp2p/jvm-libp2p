package io.libp2p.simulate.util

import kotlin.reflect.full.memberProperties

fun <K, V> Collection<Map.Entry<K, V>>.toMap() = this.map { it.key to it.value }.toMap()

fun <T> Collection<T>.countValues(): Map<T, Int> = countValuesBy { it }

fun <T, K> Collection<T>.countValuesBy(keyExtractor: (T) -> K): Map<K, Int> =
    this.groupBy { keyExtractor(it) }.mapValues { (_, list) -> list.size }

operator fun <T> List<T>.get(subIndexes: IntRange) = subList(subIndexes.first, subIndexes.last + 1)
fun <K, V> Map<K, V>.setKeys(f: (K) -> K): Map<K, V> = asSequence().map { f(it.key) to it.value }.toMap()
operator fun <K, V> Map<K, V>.plus(other: Map<K, V>): Map<K, V> =
    (asSequence() + other.asSequence()).map { it.key to it.value }.toMap()

fun <K, V> List<Map<K, V>>.transpose(): Map<K, List<V>> = flatMap { it.asIterable() }.groupBy({ it.key }, { it.value })
fun <K, V> Map<K, List<V>>.transpose(): List<Map<K, V>> {
    val list = asSequence()
        .toList()
        .flatMap { kv ->
            kv.value.mapIndexed { i, v ->
                kv.key to (i to v)
            }
        }
    val indexedMap = list.groupBy { it.second.first }
    val ret = indexedMap.map { it.value.associate { it.first to it.second.second } }
    return ret
}

fun Any.propertiesAsMap() = javaClass.kotlin.memberProperties.map { it.name to it.get(this) }.toMap()
fun <T : Comparable<T>> Collection<T>.isOrdered() =
    this
        .windowed(2) { l -> l[1] >= l[0] }
        .all { it }
