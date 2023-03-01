package io.libp2p.simulate.util

import java.lang.Long.min
import kotlin.math.roundToLong

fun <TKey, TValue, TSrc> Collection<TSrc>.groupByRangesBy(
    keyExtractor: (TSrc) -> TKey,
    valueExtractor: (TSrc) -> TValue,
    vararg ranges: ClosedRange<TKey>
): Map<ClosedRange<TKey>, List<TValue>>
        where TKey : Number, TKey : Comparable<TKey> {

    return this
        .mapNotNull { v -> ranges.firstOrNull { it.contains(keyExtractor(v)) }?.let { it to v } }
        .groupBy({ it.first }, { valueExtractor(it.second) })
        .toSortedMap(Comparator.comparing { it.start })
}

fun <TKey, TSrc> Collection<TSrc>.groupByRangesBy(
    keyExtractor: (TSrc) -> TKey,
    vararg ranges: ClosedRange<TKey>
): Map<ClosedRange<TKey>, List<TSrc>>
        where TKey : Number, TKey : Comparable<TKey> =
    groupByRangesBy(keyExtractor, { it }, *ranges)

fun <T, V> Collection<Pair<T, V>>.groupByRanges(vararg ranges: ClosedRange<T>): Map<ClosedRange<T>, List<V>>
        where T : Number, T : Comparable<T> =
    groupByRangesBy({ it.first }, { it.second }, *ranges)

fun <T> Collection<T>.countByRanges(vararg ranges: ClosedRange<T>): List<Int>
        where T : Number, T : Comparable<T> {
    val v = this
        .map { it to it }
        .groupByRangesBy({ it.first }, { it.second }, *ranges)

    return ranges.map { v[it]?.size ?: 0 }
}

fun <T> Collection<T>.countByRanges(ranges: List<ClosedRange<T>>): List<Int>
    where T : Number, T : Comparable<T> =
    countByRanges(*ranges.toTypedArray())

fun IntRange.chunked(maxSize: Int): List<IntRange> =
    LongRange(start.toLong(), endInclusive.toLong())
        .chunked(maxSize.toLong())
        .map { IntRange(it.first.toInt(), it.last.toInt()) }

fun LongRange.chunked(maxSize: Long): List<LongRange> {
    val ret = mutableListOf<LongRange>()
    var start = this.first
    while (start <= this.last) {
        val endIncl = min(this.last, start + maxSize - 1)
        ret += start..endIncl
        start = endIncl + 1
    }
    return ret
}

fun Int.pow(n: Int): Long {
    var t = 1L
    for (i in 0 until n) t *= this
    return t
}

fun Double.smartRound(meaningCount: Int = 3): Double {
    if (this <= 0.0) return this

    var cnt = 0
    var n = this
    val t = 10.pow(meaningCount)

    if (n < t) {
        while (n < t) {
            n *= 10
            cnt++
        }
        return n.roundToLong().toDouble() / 10.pow(cnt)
    } else {
        while (n > t * 10) {
            n /= 10
            cnt++
        }
        return n.roundToLong().toDouble() * 10.pow(cnt)
    }
}

fun gcd(a: Int, b: Int): Int {
    return if (a == 0) b else gcd(b % a, a)
}

fun gcd(arr: List<Int>): Int {
    var result = arr[0]
    for (element in arr) {
        result = gcd(result, element)
        if (result == 1) {
            return 1
        }
    }
    return result
}
