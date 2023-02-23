package io.libp2p.simulate.util

data class Named<T>(
    val name: String,
    val value: T
) {
    override fun toString() = name
}

fun <T> Map.Entry<String,T>.asNamed() = Named(this.key, this.value)