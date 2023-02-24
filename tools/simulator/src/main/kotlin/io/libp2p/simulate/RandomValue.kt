package io.libp2p.simulate

import java.util.Random

interface RandomValue {

    fun next(): Double

    companion object {
        fun const(constVal: Double) = object : RandomValue {
            override fun next() = constVal
        }
        fun uniform(from: Double, to: Double, rnd: Random) = object : RandomValue {
            override fun next() = from + rnd.nextDouble() * (to - from)
        }
    }
}

interface RandomDistribution {
    fun newValue(rnd: Random): RandomValue

    companion object {
        fun const(constVal: Double) = ConstRandomDistr(constVal)
        fun uniform(from: Double, to: Double) = UniformRandomDistr(from, to)
    }

    data class ConstRandomDistr(val constVal: Double) : RandomDistribution {
        override fun newValue(rnd: Random) = RandomValue.const(constVal)
    }
    data class UniformRandomDistr(val from: Double, val to: Double) : RandomDistribution {
        override fun newValue(rnd: Random) = RandomValue.uniform(from, to, rnd)
    }
}
