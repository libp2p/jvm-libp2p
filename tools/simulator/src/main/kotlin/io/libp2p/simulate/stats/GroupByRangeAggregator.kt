package io.libp2p.simulate.stats

import io.libp2p.simulate.util.chunked
import io.libp2p.simulate.util.countByRanges
import io.libp2p.simulate.util.max
import io.libp2p.simulate.util.min


class GroupByRangeAggregator() {

    constructor(values: Map<String, Collection<Long>>) : this() {
        addAll(values)
    }

    private var idGenerator = 0

    val valuesMap = LinkedHashMap<String, Collection<Long>>()

    val allValues get() = valuesMap.values.flatten()
    val minValue get() = allValues.min()
    val maxValue get() = allValues.max()

    fun addAll(values: Map<String, Collection<Long>>)  = apply {
        values.forEach { (name, values) ->
            addValues(values, name)
        }
    }

    fun addValues(values: Collection<Number>, seriesName: String = "<${idGenerator++}>") = apply {
        require(seriesName !in valuesMap)
        valuesMap[seriesName] = values.map { it.toLong() }
    }

    fun withMappedNames(mapper: (String) -> String): GroupByRangeAggregator =
        GroupByRangeAggregator(
            valuesMap.mapKeys { mapper(it.key) }
        )

    operator fun plus(other: GroupByRangeAggregator): GroupByRangeAggregator =
        GroupByRangeAggregator(
            valuesMap + other.valuesMap
        )

    fun aggregate(
        rangeSize: Long,
        boundary: LongRange = minValue..maxValue
    ): GroupByRangeAggregate {
        val ranges: List<LongRange> = boundary.chunked(rangeSize)
        val rangedValues: Map<String, List<Int>> = valuesMap
            .mapValues { (_, values) ->
                values.countByRanges(ranges)
            }
        return GroupByRangeAggregate(ranges, rangedValues)
    }

    inner class GroupByRangeAggregate(
        val ranges: List<LongRange> ,
        val rangedValues: Map<String, List<Int>>
    ) {
        init {
            require(rangedValues.values.all { it.size == ranges.size })
        }

        fun formatToString(
            printOnlyRangeStart: Boolean = true,
            printSeriesColumnHeaders: Boolean = true,
            rangeColumnHeader: String = "Ranges",
            columnDelimiter: String = "\t",
            linesDelimiter: String = "\n"
        ): String {
            var ret = ""
            if (printSeriesColumnHeaders) {
                ret += (listOf(rangeColumnHeader) + rangedValues.keys).joinToString(columnDelimiter)
                ret += linesDelimiter
            }
            for (i in ranges.indices) {
                val rangeString =
                    if (printOnlyRangeStart) {
                        ranges[i].start.toString()
                    } else {
                        ranges[i].toString()
                    }
                ret += (listOf(rangeString) + rangedValues.values.map { it[i] }).joinToString(columnDelimiter)
                ret += linesDelimiter
            }
            return ret
        }
    }
}

