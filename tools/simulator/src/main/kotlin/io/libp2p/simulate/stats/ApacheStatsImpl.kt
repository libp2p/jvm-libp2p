package io.libp2p.simulate.stats

import io.libp2p.simulate.util.smartRound
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math3.stat.descriptive.SummaryStatistics

data class BasicStatsImpl(
    private val aStats: SummaryStatistics = SummaryStatistics()
) : WritableStats {

    override fun addValue(value: Double) {
        aStats.addValue(value)
    }

    override fun reset() {
        aStats.clear()
    }

    override fun getStatisticalSummary() = aStats

    override fun plus(other: Stats): Stats = TODO()
}

data class DescriptiveStatsImpl(
    private val aStats: DescriptiveStatistics = DescriptiveStatistics()
) : WritableStats {

    override fun addValue(value: Double) {
        aStats.addValue(value)
    }

    override fun reset() {
        aStats.clear()
    }

    override fun getStatisticalSummary() = aStats
    override fun getDescriptiveStatistics() = aStats

    override fun plus(other: Stats): Stats {
        other as DescriptiveStatsImpl
        return DescriptiveStatsImpl(DescriptiveStatistics(aStats.values + other.aStats.values))
    }

    override fun toString(): String {
        return "" + getCount() + ":" +
            getDescriptiveStatistics().min.smartRound() + "/" +
            getDescriptiveStatistics().getPercentile(50.0).smartRound() + "/" +
            getDescriptiveStatistics().max.smartRound()
    }
}
