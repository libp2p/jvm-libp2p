package io.libp2p.simulate.stats

class SimpleStatsImpl : WritableStats {
    private var countVar = 0L
    private var sumVar = 0.0

    override fun addValue(value: Double) {
        countVar++
        sumVar += value
    }

    override fun reset() {
        countVar = 0L
        sumVar = 0.0
    }

    override fun getCount() = countVar
    override fun getSum() = sumVar

    override fun plus(other: Stats) =
        SimpleStatsImpl().also {
            it.countVar = getCount() + other.getCount()
            it.sumVar = getSum() + other.getSum()
        }
}
