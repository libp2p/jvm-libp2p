package io.libp2p.simulate.stats

interface WritableStats : Stats {

    fun addValue(value: Double)
    fun addValue(value: Int) = addValue(value.toDouble())
    fun addValue(value: Long) = addValue(value.toDouble())
    operator fun plusAssign(value: Number) = addValue(value.toDouble())

    fun addAllValues(values: Collection<Number>) = values.forEach { addValue(it.toDouble()) }
    operator fun plusAssign(values: Collection<Number>) = addAllValues(values)

    fun reset()
}
