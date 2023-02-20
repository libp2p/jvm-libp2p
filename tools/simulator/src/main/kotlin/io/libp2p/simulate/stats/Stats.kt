package io.libp2p.simulate.stats

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math3.stat.descriptive.StatisticalSummary

interface Stats {

    fun getCount(): Long = getStatisticalSummary().n

    fun getSum(): Double = getStatisticalSummary().sum

    fun getStatisticalSummary(): StatisticalSummary = TODO()

    fun getDescriptiveStatistics(): DescriptiveStatistics = TODO()

    // One of the following should be overridden
    operator fun plus(other: Stats): Stats = this + listOf(other)
    operator fun plus(others: List<Stats>): Stats =
        (listOf(this) + others).reduce { a, b -> a + b }
}
