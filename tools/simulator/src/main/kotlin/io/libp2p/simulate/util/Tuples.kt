package io.libp2p.simulate.util

data class Quadriple<out T1, out T2, out T3, out T4> (
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4
)

data class Tuple5<out T1, out T2, out T3, out T4, out T5> (
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4,
    val fifth: T5
)

data class Tuple6<out T1, out T2, out T3, out T4, out T5, out T6> (
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4,
    val fifth: T5,
    val sixth: T6
)

